#include <pebble.h>

#define MAX_POINTS 20

static Window *s_window;
static Layer *s_canvas_layer;

static uint16_t s_screen_w = 0;
static uint16_t s_screen_h = 0;

static uint8_t s_num_points = 0;
static GPoint s_points[MAX_POINTS];
static uint8_t s_current_loc_index = 0;
static uint8_t s_destination_index = 255;
static int32_t s_bearing = 0;

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

static void update_data_from_dict(DictionaryIterator *iter) {
  Tuple *tuple = dict_find(iter, MESSAGE_KEY_NUM_ROUTE_POINTS);
  if (tuple) {
    s_num_points = tuple->value->uint8;
    if (s_num_points > MAX_POINTS) s_num_points = MAX_POINTS;

    tuple = dict_find(iter, MESSAGE_KEY_ROUTE_POINTS);
    if (tuple && tuple->length >= s_num_points * 2) {
      uint8_t *data = tuple->value->data;
      uint16_t padding = 10;
      uint16_t usable_w = s_screen_w - 2 * padding;
      uint16_t usable_h = s_screen_h - 2 * padding;
      for (int i = 0; i < s_num_points; i++) {
        uint8_t x = data[i * 2];
        uint8_t y = data[i * 2 + 1];
        s_points[i].x = padding + ((int)x * usable_w / 255);
        s_points[i].y = padding + ((int)y * usable_h / 255);
      }
    }
  }

  tuple = dict_find(iter, MESSAGE_KEY_CURRENT_LOC_INDEX);
  if (tuple) s_current_loc_index = tuple->value->uint8;

  tuple = dict_find(iter, MESSAGE_KEY_DESTINATION_INDEX);
  if (tuple) s_destination_index = tuple->value->uint8;

  tuple = dict_find(iter, MESSAGE_KEY_BEARING);
  if (tuple) s_bearing = tuple->value->int32;

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
}

static const char* get_turn_arrow(uint8_t dir) {
  switch (dir) {
    case 1: return "\u2191"; // STRAIGHT
    case 2: return "\u2196"; // SLIGHT_LEFT
    case 3: return "\u2190"; // LEFT
    case 4: return "\u21d0"; // SHARP_LEFT
    case 5: return "\u2197"; // SLIGHT_RIGHT
    case 6: return "\u2192"; // RIGHT
    case 7: return "\u21d2"; // SHARP_RIGHT
    case 8: return "\u21ba"; // UTURN
    default: return "";
  }
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
  GPoint center = grect_center_point(&bounds);

  GPoint rotated[MAX_POINTS];
  for (int i = 0; i < s_num_points; i++) {
    rotated[i] = rotate_point(s_points[i], center, s_bearing);
  }

  // Draw route polyline
  if (s_num_points >= 2) {
    graphics_context_set_stroke_color(ctx, GColorYellow);
    graphics_context_set_stroke_width(ctx, 2);
    for (int i = 0; i < s_num_points - 1; i++) {
      graphics_draw_line(ctx, rotated[i], rotated[i + 1]);
    }
  }

  // Draw destination if visible in viewport
  if (s_destination_index < s_num_points) {
    graphics_context_set_fill_color(ctx, GColorRed);
    graphics_fill_circle(ctx, rotated[s_destination_index], 4);
  }

  // Draw current location
  if (s_current_loc_index < s_num_points) {
    graphics_context_set_fill_color(ctx, GColorGreen);
    graphics_fill_circle(ctx, rotated[s_current_loc_index], 5);
  }

  // Turn arrow
  const char *arrow = get_turn_arrow(s_turn_direction);
  if (arrow[0] != '\0') {
    graphics_context_set_text_color(ctx, GColorYellow);
    graphics_draw_text(ctx, arrow, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD),
                       GRect(0, bounds.size.h * 0.05, bounds.size.w, 40),
                       GTextOverflowModeWordWrap, GTextAlignmentCenter, NULL);
  }

  // Distance to turn
  char dist_buf[16];
  format_distance(s_distance_to_turn, dist_buf, sizeof(dist_buf));
  graphics_context_set_text_color(ctx, GColorWhite);
  graphics_draw_text(ctx, dist_buf, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD),
                     GRect(0, bounds.size.h * 0.25, bounds.size.w, 30),
                     GTextOverflowModeWordWrap, GTextAlignmentCenter, NULL);

  // Street name
  if (s_street_name[0] != '\0') {
    graphics_draw_text(ctx, s_street_name, fonts_get_system_font(FONT_KEY_GOTHIC_14),
                       GRect(0, bounds.size.h * 0.72, bounds.size.w, 30),
                       GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
  }

  // Remaining distance
  char remaining_buf[16];
  format_distance(s_distance_remaining, remaining_buf, sizeof(remaining_buf));
  graphics_draw_text(ctx, remaining_buf, fonts_get_system_font(FONT_KEY_GOTHIC_14),
                     GRect(0, bounds.size.h * 0.85, bounds.size.w, 30),
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

  window_set_background_color(window, GColorBlack);

  s_canvas_layer = layer_create(bounds);
  layer_set_update_proc(s_canvas_layer, canvas_update_proc);
  layer_add_child(window_layer, s_canvas_layer);

  app_message_register_inbox_received(inbox_received_callback);
  app_message_register_inbox_dropped(inbox_dropped_callback);
  app_message_register_outbox_sent(outbox_sent_callback);
  app_message_register_outbox_failed(outbox_failed_callback);
  app_message_open(256, 64);
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
