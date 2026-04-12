#include <pebble.h>
#include <math.h>

#define MAX_POINTS 60
#define MAX_ROAD_DATA 700
#define MESSAGE_KEY_ZOOM 22
#define MESSAGE_KEY_SCREEN_WIDTH 10
#define MESSAGE_KEY_SCREEN_HEIGHT 11

static Window *s_window;
static Layer *s_canvas_layer;

static uint16_t s_screen_w = 0;
static uint16_t s_screen_h = 0;

static uint8_t s_num_points = 0;
static GPoint s_points[MAX_POINTS];
static uint8_t s_destination_index = 255;

static uint8_t s_road_data[MAX_ROAD_DATA];
static uint8_t s_road_data_len = 0;
static bool s_has_roads = false;

static uint8_t s_turn_direction = 0;
static int32_t s_distance_to_turn = 0;
static int32_t s_distance_remaining = 0;
static int32_t s_bearing = 0;
static char s_street_name[21] = "";

static GBitmap *s_bitmap_arrow_straight;
static GBitmap *s_bitmap_arrow_slight_left;
static GBitmap *s_bitmap_arrow_left;
static GBitmap *s_bitmap_arrow_sharp_left;
static GBitmap *s_bitmap_arrow_slight_right;
static GBitmap *s_bitmap_arrow_right;
static GBitmap *s_bitmap_arrow_sharp_right;
static GBitmap *s_bitmap_arrow_uturn;
static GBitmap *s_bitmap_arrow_none;

static GPoint byte_to_screen(uint8_t x, uint8_t y) {
  uint16_t padding = 10;
  uint16_t usable_w = s_screen_w - 2 * padding;
  uint16_t usable_h = s_screen_h - 2 * padding;
  GPoint p;
  p.x = padding + ((int)x * usable_w / 255);
  p.y = padding + ((int)y * usable_h / 255);
  return p;
}

static void update_data_from_dict(DictionaryIterator *iter) {
  Tuple *tuple = dict_find(iter, MESSAGE_KEY_NUM_ROUTE_POINTS);
  if (tuple) {
    s_num_points = tuple->value->uint8;
    if (s_num_points > MAX_POINTS) s_num_points = MAX_POINTS;

    tuple = dict_find(iter, MESSAGE_KEY_ROUTE_POINTS);
    if (tuple && tuple->length >= s_num_points * 2) {
      uint8_t *data = tuple->value->data;
      for (int i = 0; i < s_num_points; i++) {
        s_points[i] = byte_to_screen(data[i * 2], data[i * 2 + 1]);
      }
    }
  }

  tuple = dict_find(iter, MESSAGE_KEY_DESTINATION_INDEX);
  if (tuple) s_destination_index = tuple->value->uint8;

  tuple = dict_find(iter, MESSAGE_KEY_TURN_DIRECTION);
  if (tuple) s_turn_direction = tuple->value->uint8;

  tuple = dict_find(iter, MESSAGE_KEY_DISTANCE_TO_TURN);
  if (tuple) s_distance_to_turn = tuple->value->int32;

  tuple = dict_find(iter, MESSAGE_KEY_DISTANCE_REMAINING);
  if (tuple) s_distance_remaining = tuple->value->int32;

  tuple = dict_find(iter, MESSAGE_KEY_BEARING);
  if (tuple) s_bearing = tuple->value->int32;

  tuple = dict_find(iter, MESSAGE_KEY_STREET_NAME);
  if (tuple) {
    strncpy(s_street_name, tuple->value->cstring, sizeof(s_street_name) - 1);
    s_street_name[sizeof(s_street_name) - 1] = '\0';
  }

  tuple = dict_find(iter, MESSAGE_KEY_ROAD_POINTS);
  Tuple *road_state = dict_find(iter, MESSAGE_KEY_HAS_ROADS);
  if (road_state && road_state->value->uint8 == 0) {
    s_has_roads = false;
    s_road_data_len = 0;
  } else if (tuple) {
    uint16_t len = tuple->length;
    if (len <= MAX_ROAD_DATA) {
      memcpy(s_road_data, tuple->value->data, len);
      s_road_data_len = len;
      s_has_roads = len > 0;
    }
  }
}

static GBitmap* bitmap_for_turn(uint8_t dir) {
  switch (dir) {
    case 1: return s_bitmap_arrow_straight;
    case 2: return s_bitmap_arrow_slight_left;
    case 3: return s_bitmap_arrow_left;
    case 4: return s_bitmap_arrow_sharp_left;
    case 5: return s_bitmap_arrow_slight_right;
    case 6: return s_bitmap_arrow_right;
    case 7: return s_bitmap_arrow_sharp_right;
    case 8: return s_bitmap_arrow_uturn;
    default: return s_bitmap_arrow_none;
  }
}

static void format_distance(int32_t meters, char *buf, size_t size) {
  if (meters <= 0) {
    snprintf(buf, size, "--m");
    return;
  }
  if (meters < 1000) {
    snprintf(buf, size, "%dm", (int)meters);
  } else if (meters < 10000) {
    int km_whole = meters / 1000;
    int km_tenth = ((meters % 1000) + 50) / 100;
    if (km_tenth >= 10) {
      km_whole++;
      km_tenth = 0;
    }
    snprintf(buf, size, "%d.%dkm", km_whole, km_tenth);
  } else {
    int km = (meters + 500) / 1000;
    snprintf(buf, size, "%dkm", km);
  }
}

static uint8_t road_half_width_for_class(uint8_t road_class) {
  switch (road_class) {
    case 3: return 4;
    case 2: return 3;
    case 1: return 2;
    default: return 1;
  }
}

static void draw_bearing_arrow(GContext *ctx, GPoint center) {
  GPoint tip = { center.x, center.y - 10 };
  GPoint right_wing = { center.x + 7, center.y + 5 };
  GPoint right_stem = { center.x + 3, center.y + 5 };
  GPoint bottom = { center.x, center.y + 8 };
  GPoint left_stem = { center.x - 3, center.y + 5 };
  GPoint left_wing = { center.x - 7, center.y + 5 };

  GPoint arrow_pts[6] = { tip, right_wing, right_stem, bottom, left_stem, left_wing };
  GPath path = {
    .num_points = 6,
    .points = arrow_pts,
    .rotation = 0,
    .offset = GPointZero,
  };

#ifdef PBL_COLOR
  graphics_context_set_fill_color(ctx, GColorRed);
#else
  graphics_context_set_fill_color(ctx, GColorBlack);
#endif
  gpath_draw_filled(ctx, &path);
}

static void draw_road_filled(GContext *ctx, GPoint p0, GPoint p1, uint8_t road_class) {
  int dx = p1.x - p0.x;
  int dy = p1.y - p0.y;
  float length = sqrtf((float)(dx * dx + dy * dy));
  if (length < 0.5f) {
    return;
  }

  uint8_t half_width = road_half_width_for_class(road_class);
  float offset_x = (-(float)dy / length) * half_width;
  float offset_y = ((float)dx / length) * half_width;

  GPoint quad[4] = {
    GPoint((int16_t)lroundf(p0.x + offset_x), (int16_t)lroundf(p0.y + offset_y)),
    GPoint((int16_t)lroundf(p1.x + offset_x), (int16_t)lroundf(p1.y + offset_y)),
    GPoint((int16_t)lroundf(p1.x - offset_x), (int16_t)lroundf(p1.y - offset_y)),
    GPoint((int16_t)lroundf(p0.x - offset_x), (int16_t)lroundf(p0.y - offset_y)),
  };

  GPath path = {
    .num_points = 4,
    .points = quad,
    .rotation = 0,
    .offset = GPointZero,
  };
#ifdef PBL_COLOR
  graphics_context_set_fill_color(ctx, GColorCyan);
#else
  graphics_context_set_fill_color(ctx, GColorBlack);
#endif
  gpath_draw_filled(ctx, &path);
}

static void canvas_update_proc(Layer *layer, GContext *ctx) {
  GRect bounds = layer_get_bounds(layer);
  GPoint screen_center = GPoint(bounds.size.w / 2, bounds.size.h * 4 / 5);

  graphics_context_set_fill_color(ctx, GColorWhite);
  graphics_fill_rect(ctx, bounds, 0, GCornerNone);

  if (s_has_roads) {
    int ri = 0;
    GPoint road_prev;
    bool road_has_prev = false;
    bool expecting_class = true;
    uint8_t road_class = 0;
    while (ri + 1 < s_road_data_len) {
      if (expecting_class) {
        road_class = s_road_data[ri];
        ri += 1;
        road_has_prev = false;
        expecting_class = false;
        continue;
      }
      uint8_t rx = s_road_data[ri];
      uint8_t ry = s_road_data[ri + 1];
      ri += 2;
      if (rx == 0xFF && ry == 0xFF) {
        road_has_prev = false;
        expecting_class = true;
        continue;
      }
      GPoint road_p = byte_to_screen(rx, ry);
      if (road_has_prev) {
        draw_road_filled(ctx, road_prev, road_p, road_class);
      }
      road_prev = road_p;
      road_has_prev = true;
    }
  }

  if (s_num_points >= 2) {
    graphics_context_set_stroke_color(ctx, GColorBlack);
    graphics_context_set_stroke_width(ctx, 3);
    for (int i = 0; i < s_num_points - 1; i++) {
      graphics_draw_line(ctx, s_points[i], s_points[i + 1]);
    }
  }

  if (s_destination_index < s_num_points) {
    graphics_context_set_fill_color(ctx, GColorRed);
    graphics_fill_circle(ctx, s_points[s_destination_index], 4);
  }

  draw_bearing_arrow(ctx, screen_center);

  int indicator_x = 8;
  int indicator_y = bounds.size.h - 55;
  int indicator_size = 36;
  
  GRect bg_rect = GRect(indicator_x - 4, indicator_y - 4, indicator_size + 8, indicator_size + 28);
  graphics_context_set_fill_color(ctx, GColorWhite);
  graphics_fill_rect(ctx, bg_rect, 4, GCornersAll);
  graphics_context_set_stroke_color(ctx, GColorBlack);
  graphics_context_set_stroke_width(ctx, 1);
  graphics_draw_rect(ctx, bg_rect);

  GBitmap *arrow = bitmap_for_turn(s_turn_direction);
  if (arrow) {
    GSize size = gbitmap_get_bounds(arrow).size;
    GRect dest = GRect(
      indicator_x + indicator_size / 2 - size.w / 2,
      indicator_y + indicator_size / 2 - 2 - size.h / 2,
      size.w,
      size.h
    );
    graphics_draw_bitmap_in_rect(ctx, arrow, dest);
  }

  char dist_buf[16];
  format_distance(s_distance_to_turn, dist_buf, sizeof(dist_buf));
  graphics_context_set_text_color(ctx, GColorBlack);
  graphics_draw_text(ctx, dist_buf, fonts_get_system_font(FONT_KEY_GOTHIC_14_BOLD),
                     GRect(indicator_x - 4, indicator_y + indicator_size + 2, indicator_size + 8, 18),
                     GTextOverflowModeWordWrap, GTextAlignmentCenter, NULL);
}

static void inbox_received_callback(DictionaryIterator *iter, void *context) {
  update_data_from_dict(iter);
  APP_LOG(APP_LOG_LEVEL_INFO, "Msg rcv pts=%d roads=%d bearing=%d", s_num_points, s_road_data_len, s_bearing);
  if (s_canvas_layer) {
    layer_mark_dirty(s_canvas_layer);
  }
}

static void outbox_sent_callback(DictionaryIterator *iter, void *context) {
}

static void outbox_failed_callback(DictionaryIterator *iter, AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_ERROR, "Outbox failed: %d", (int)reason);
}

static void send_zoom_to_phone(int8_t delta) {
  AppMessageResult result;
  DictionaryIterator *iter;
  result = app_message_outbox_begin(&iter);
  if (result != APP_MSG_OK) return;
  
  dict_write_int8(iter, MESSAGE_KEY_ZOOM, delta);
  result = app_message_outbox_send();
  if (result != APP_MSG_OK) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Failed to send zoom: %d", result);
  }
}

static void send_screen_size_to_phone(void) {
  AppMessageResult result;
  DictionaryIterator *iter;
  result = app_message_outbox_begin(&iter);
  if (result != APP_MSG_OK) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Failed to begin screen size msg: %d", result);
    return;
  }
  
  dict_write_int16(iter, MESSAGE_KEY_SCREEN_WIDTH, s_screen_w);
  dict_write_int16(iter, MESSAGE_KEY_SCREEN_HEIGHT, s_screen_h);
  result = app_message_outbox_send();
  if (result != APP_MSG_OK) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "Failed to send screen size: %d", result);
  } else {
    APP_LOG(APP_LOG_LEVEL_INFO, "Sent screen size to phone: %dx%d", s_screen_w, s_screen_h);
  }
}

static void retry_send_screen_size(void *context) {
  static int retries = 0;
  if (retries < 5) {
    send_screen_size_to_phone();
    retries++;
    app_timer_register(500, retry_send_screen_size, NULL);
  } else {
    retries = 0;
  }
}

static void inbox_dropped_callback(AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_ERROR, "Inbox dropped: %d", (int)reason);
}

static void up_click_handler(ClickRecognizerRef recognizer, void *context) {
  send_zoom_to_phone(1);
}

static void down_click_handler(ClickRecognizerRef recognizer, void *context) {
  send_zoom_to_phone(-1);
}

static void click_config_provider(void *context) {
  window_single_click_subscribe(BUTTON_ID_UP, up_click_handler);
  window_single_click_subscribe(BUTTON_ID_DOWN, down_click_handler);
}

static void window_load(Window *window) {
  Layer *window_layer = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(window_layer);
  s_screen_w = bounds.size.w;
  s_screen_h = bounds.size.h;

  s_bitmap_arrow_straight = gbitmap_create_with_resource(RESOURCE_ID_ARROW_STRAIGHT);
  s_bitmap_arrow_slight_left = gbitmap_create_with_resource(RESOURCE_ID_ARROW_SLIGHT_LEFT);
  s_bitmap_arrow_left = gbitmap_create_with_resource(RESOURCE_ID_ARROW_LEFT);
  s_bitmap_arrow_sharp_left = gbitmap_create_with_resource(RESOURCE_ID_ARROW_SHARP_LEFT);
  s_bitmap_arrow_slight_right = gbitmap_create_with_resource(RESOURCE_ID_ARROW_SLIGHT_RIGHT);
  s_bitmap_arrow_right = gbitmap_create_with_resource(RESOURCE_ID_ARROW_RIGHT);
  s_bitmap_arrow_sharp_right = gbitmap_create_with_resource(RESOURCE_ID_ARROW_SHARP_RIGHT);
  s_bitmap_arrow_uturn = gbitmap_create_with_resource(RESOURCE_ID_ARROW_UTURN);
  s_bitmap_arrow_none = gbitmap_create_with_resource(RESOURCE_ID_ARROW_NONE);

  window_set_background_color(window, GColorWhite);

  s_canvas_layer = layer_create(bounds);
  layer_set_update_proc(s_canvas_layer, canvas_update_proc);
  layer_add_child(window_layer, s_canvas_layer);

  app_message_register_inbox_received(inbox_received_callback);
  app_message_register_inbox_dropped(inbox_dropped_callback);
  app_message_register_outbox_sent(outbox_sent_callback);
  app_message_register_outbox_failed(outbox_failed_callback);
  const uint32_t inbox_size = 2048;
  const uint32_t outbox_size = 256;
  AppMessageResult open_result = app_message_open(inbox_size, outbox_size);
  if (open_result != APP_MSG_OK) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "app_message_open failed: %d", (int)open_result);
  }

  send_screen_size_to_phone();
  app_timer_register(1000, retry_send_screen_size, NULL);

  window_set_click_config_provider(s_window, click_config_provider);
}

static void window_unload(Window *window) {
  layer_destroy(s_canvas_layer);
  gbitmap_destroy(s_bitmap_arrow_straight);
  gbitmap_destroy(s_bitmap_arrow_slight_left);
  gbitmap_destroy(s_bitmap_arrow_left);
  gbitmap_destroy(s_bitmap_arrow_sharp_left);
  gbitmap_destroy(s_bitmap_arrow_slight_right);
  gbitmap_destroy(s_bitmap_arrow_right);
  gbitmap_destroy(s_bitmap_arrow_sharp_right);
  gbitmap_destroy(s_bitmap_arrow_uturn);
  gbitmap_destroy(s_bitmap_arrow_none);
}

static void init() {
  s_window = window_create();
  window_set_window_handlers(s_window, (WindowHandlers) {
    .load = window_load,
    .unload = window_unload,
  });
  window_stack_push(s_window, true);
}

static void deinit() {
  window_destroy(s_window);
}

int main() {
  init();
  app_event_loop();
  deinit();
  return 0;
}
