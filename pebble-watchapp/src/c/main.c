#include <pebble.h>

#define MAX_POINTS 60
#define MAX_ROAD_DATA 360

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
static char s_street_name[21] = "";

static GPoint rotate_point(GPoint p, GPoint center, int32_t angle_hundredths) {
  if (angle_hundredths == 0) return p;

  int32_t angle = (-angle_hundredths * TRIG_MAX_ANGLE) / 36000;
  int32_t c = cos_lookup(angle);
  int32_t s = sin_lookup(angle);

  int dx = p.x - center.x;
  int dy = p.y - center.y;

  int dx_rot = (int)((dx * c - dy * s) / TRIG_MAX_RATIO);
  int dy_rot = (int)((dx * s + dy * c) / TRIG_MAX_RATIO);

  return GPoint(center.x + dx_rot, center.y + dy_rot);
}

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

static void draw_turn_arrow_shape(GContext *ctx, GPoint center, uint8_t dir) {
  if (dir == 0) return;

  int32_t angle = 0;
  switch (dir) {
    case 2: angle = -4500; break;
    case 3: angle = -9000; break;
    case 4: angle = -13500; break;
    case 5: angle = 4500; break;
    case 6: angle = 9000; break;
    case 7: angle = 13500; break;
    case 8: angle = 18000; break;
    default: angle = 0; break;
  }

  GPoint tip = { center.x, center.y - 12 };
  GPoint left = { center.x - 8, center.y + 4 };
  GPoint right = { center.x + 8, center.y + 4 };
  GPoint stem = { center.x, center.y + 8 };

  tip = rotate_point(tip, center, angle);
  left = rotate_point(left, center, angle);
  right = rotate_point(right, center, angle);
  stem = rotate_point(stem, center, angle);

  graphics_context_set_stroke_color(ctx, GColorBlack);
  graphics_context_set_stroke_width(ctx, 3);
  graphics_draw_line(ctx, tip, left);
  graphics_draw_line(ctx, tip, right);
  graphics_draw_line(ctx, left, stem);
  graphics_draw_line(ctx, right, stem);
}

static void format_distance(int32_t meters, char *buf, size_t size) {
  if (meters < 1000) {
    snprintf(buf, size, "%dm", (int)meters);
  } else {
    float km = meters / 1000.0f;
    snprintf(buf, size, "%.1fkm", km);
  }
}

static void canvas_update_proc(Layer *layer, GContext *ctx) {
  GRect bounds = layer_get_bounds(layer);
  GPoint screen_center = GPoint(bounds.size.w / 2, bounds.size.h / 2);

  graphics_context_set_stroke_color(ctx, GColorBlack);
  graphics_context_set_stroke_width(ctx, 2);
  if (s_has_roads) {
    int ri = 0;
    GPoint road_prev;
    bool road_has_prev = false;
    while (ri + 1 < s_road_data_len) {
      uint8_t rx = s_road_data[ri];
      uint8_t ry = s_road_data[ri + 1];
      ri += 2;
      if (rx == 0xFF && ry == 0xFF) {
        road_has_prev = false;
        continue;
      }
      GPoint road_p = byte_to_screen(rx, ry);
      if (road_has_prev) {
        graphics_draw_line(ctx, road_prev, road_p);
      }
      road_prev = road_p;
      road_has_prev = true;
    }
  }

  if (s_num_points >= 2) {
    graphics_context_set_stroke_color(ctx, GColorVividCerulean);
    graphics_context_set_stroke_width(ctx, 3);
    for (int i = 0; i < s_num_points - 1; i++) {
      graphics_draw_line(ctx, s_points[i], s_points[i + 1]);
    }
  }

  if (s_destination_index < s_num_points) {
    graphics_context_set_fill_color(ctx, GColorRed);
    graphics_fill_circle(ctx, s_points[s_destination_index], 4);
  }

  graphics_context_set_fill_color(ctx, GColorGreen);
  graphics_fill_circle(ctx, screen_center, 5);

  draw_turn_arrow_shape(ctx, GPoint(bounds.size.w / 2, bounds.size.h - 38), s_turn_direction);

  char dist_buf[16];
  format_distance(s_distance_to_turn, dist_buf, sizeof(dist_buf));
  graphics_context_set_text_color(ctx, GColorBlack);
  graphics_draw_text(ctx, dist_buf, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
                     GRect(0, bounds.size.h - 18, bounds.size.w, 18),
                     GTextOverflowModeWordWrap, GTextAlignmentCenter, NULL);
}

static void inbox_received_callback(DictionaryIterator *iter, void *context) {
  update_data_from_dict(iter);
  if (s_canvas_layer) {
    layer_mark_dirty(s_canvas_layer);
  }
}

static void outbox_sent_callback(DictionaryIterator *iter, void *context) {
}

static void outbox_failed_callback(DictionaryIterator *iter, AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_ERROR, "Outbox failed: %d", (int)reason);
}

static void inbox_dropped_callback(AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_ERROR, "Inbox dropped: %d", (int)reason);
}

static void window_load(Window *window) {
  Layer *window_layer = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(window_layer);
  s_screen_w = bounds.size.w;
  s_screen_h = bounds.size.h;

  window_set_background_color(window, GColorWhite);

  s_canvas_layer = layer_create(bounds);
  layer_set_update_proc(s_canvas_layer, canvas_update_proc);
  layer_add_child(window_layer, s_canvas_layer);

  app_message_register_inbox_received(inbox_received_callback);
  app_message_register_inbox_dropped(inbox_dropped_callback);
  app_message_register_outbox_sent(outbox_sent_callback);
  app_message_register_outbox_failed(outbox_failed_callback);
  app_message_open(app_message_inbox_size_maximum(), app_message_outbox_size_maximum());
}

static void window_unload(Window *window) {
  layer_destroy(s_canvas_layer);
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
