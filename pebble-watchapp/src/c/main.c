#include <pebble.h>

#define MAX_ROUTE_POINTS 20
#define MAX_STREET_LEN 32

static Window *s_window;
static Layer *s_map_layer;
static TextLayer *s_turn_layer;
static TextLayer *s_distance_layer;
static TextLayer *s_remaining_layer;
static TextLayer *s_street_layer;

static uint8_t s_route_points[MAX_ROUTE_POINTS * 2];
static uint8_t s_num_route_points = 0;
static uint8_t s_current_loc_index = 0;
static uint8_t s_turn_direction = 0;
static int32_t s_distance_to_turn = 0;
static int32_t s_distance_remaining = 0;
static char s_street_name[MAX_STREET_LEN] = "";
static bool s_map_visible = true;

static char s_turn_text[8] = "";
static char s_distance_text[16] = "";
static char s_remaining_text[16] = "";

static const char *TURN_ARROWS[] = {
  "",      // NONE
  "\u2191", // STRAIGHT (up arrow)
  "\u2196", // SLIGHT_LEFT
  "\u2190", // LEFT
  "\u21d0", // SHARP_LEFT
  "\u2197", // SLIGHT_RIGHT
  "\u2192", // RIGHT
  "\u21d2", // SHARP_RIGHT
  "\u21ba"  // UTURN
};

static void format_distance(int32_t meters, char *buf, size_t buf_size) {
  if (meters < 1000) {
    snprintf(buf, buf_size, "%dm", (int)meters);
  } else {
    int km = (int)meters / 1000;
    int dec = ((int)meters % 1000) / 100;
    snprintf(buf, buf_size, "%d.%dkm", km, dec);
  }
}

static void update_text_layers() {
  if (s_turn_direction < sizeof(TURN_ARROWS) / sizeof(TURN_ARROWS[0])) {
    strncpy(s_turn_text, TURN_ARROWS[s_turn_direction], sizeof(s_turn_text));
  } else {
    s_turn_text[0] = '\0';
  }
  text_layer_set_text(s_turn_layer, s_turn_text);

  format_distance(s_distance_to_turn, s_distance_text, sizeof(s_distance_text));
  text_layer_set_text(s_distance_layer, s_distance_text);

  format_distance(s_distance_remaining, s_remaining_text, sizeof(s_remaining_text));
  text_layer_set_text(s_remaining_layer, s_remaining_text);

  text_layer_set_text(s_street_layer, s_street_name);
}

static void layout_ui() {
  GRect bounds = layer_get_bounds(window_get_root_layer(s_window));
  int16_t width = bounds.size.w;
  int16_t height = bounds.size.h;

  if (s_map_visible) {
    // Map mode: map fills window, text overlays at bottom
    layer_set_frame(s_map_layer, bounds);
    layer_set_hidden(s_map_layer, false);

    int16_t text_h = 34;
    int16_t arrow_w = 30;

    layer_set_frame(text_layer_get_layer(s_turn_layer), GRect(4, height - text_h - 2, arrow_w, text_h));
    text_layer_set_font(s_turn_layer, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD));

    layer_set_frame(text_layer_get_layer(s_distance_layer), GRect(arrow_w + 8, height - text_h - 2, width - arrow_w - 12, text_h));
    text_layer_set_font(s_distance_layer, fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD));

    layer_set_frame(text_layer_get_layer(s_remaining_layer), GRect(4, height - text_h - 18, width - 8, 18));
    text_layer_set_font(s_remaining_layer, fonts_get_system_font(FONT_KEY_GOTHIC_14));

    layer_set_frame(text_layer_get_layer(s_street_layer), GRect(4, 2, width - 8, 18));
    text_layer_set_font(s_street_layer, fonts_get_system_font(FONT_KEY_GOTHIC_14));
  } else {
    // Turn-only mode: hide map, large centered text
    layer_set_hidden(s_map_layer, true);

    int16_t turn_h = 60;
    int16_t dist_h = 34;
    int16_t rem_h = 20;
    int16_t street_h = 20;

    int16_t y = (height - turn_h - dist_h - rem_h - street_h) / 2;

    layer_set_frame(text_layer_get_layer(s_turn_layer), GRect(0, y, width, turn_h));
    text_layer_set_font(s_turn_layer, fonts_get_system_font(FONT_KEY_BITHAM_42_BOLD));
    text_layer_set_text_alignment(s_turn_layer, GTextAlignmentCenter);

    y += turn_h;
    layer_set_frame(text_layer_get_layer(s_distance_layer), GRect(0, y, width, dist_h));
    text_layer_set_font(s_distance_layer, fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD));
    text_layer_set_text_alignment(s_distance_layer, GTextAlignmentCenter);

    y += dist_h;
    layer_set_frame(text_layer_get_layer(s_street_layer), GRect(0, y, width, street_h));
    text_layer_set_font(s_street_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18));
    text_layer_set_text_alignment(s_street_layer, GTextAlignmentCenter);

    y += street_h;
    layer_set_frame(text_layer_get_layer(s_remaining_layer), GRect(0, y, width, rem_h));
    text_layer_set_font(s_remaining_layer, fonts_get_system_font(FONT_KEY_GOTHIC_14));
    text_layer_set_text_alignment(s_remaining_layer, GTextAlignmentCenter);
  }
}

static void map_layer_update_proc(Layer *layer, GContext *ctx) {
  if (!s_map_visible || s_num_route_points < 2) return;

  GRect bounds = layer_get_bounds(layer);
  int16_t w = bounds.size.w;
  int16_t h = bounds.size.h;

  // Draw route polyline
  graphics_context_set_stroke_color(ctx, GColorYellow);
  graphics_context_set_stroke_width(ctx, 2);

  for (int i = 0; i < s_num_route_points - 1; i++) {
    int x0 = (s_route_points[i * 2] * w) / 255;
    int y0 = (s_route_points[i * 2 + 1] * h) / 255;
    int x1 = (s_route_points[(i + 1) * 2] * w) / 255;
    int y1 = (s_route_points[(i + 1) * 2 + 1] * h) / 255;
    graphics_draw_line(ctx, GPoint(x0, y0), GPoint(x1, y1));
  }

  // Draw current location
  if (s_current_loc_index < s_num_route_points) {
    int cx = (s_route_points[s_current_loc_index * 2] * w) / 255;
    int cy = (s_route_points[s_current_loc_index * 2 + 1] * h) / 255;
    graphics_context_set_fill_color(ctx, GColorGreen);
    graphics_fill_circle(ctx, GPoint(cx, cy), 4);
  }

  // Draw destination (last point)
  int lx = (s_route_points[(s_num_route_points - 1) * 2] * w) / 255;
  int ly = (s_route_points[(s_num_route_points - 1) * 2 + 1] * h) / 255;
  graphics_context_set_fill_color(ctx, GColorRed);
  graphics_fill_circle(ctx, GPoint(lx, ly), 3);
}

static void inbox_received_callback(DictionaryIterator *iter, void *context) {
  APP_LOG(APP_LOG_LEVEL_INFO, "Inbox received!");

  Tuple *tuple = dict_find(iter, MESSAGE_KEY_TURN_DIRECTION);
  if (tuple) {
    s_turn_direction = tuple->value->uint8;
    APP_LOG(APP_LOG_LEVEL_INFO, "TURN_DIRECTION=%d", s_turn_direction);
  } else {
    APP_LOG(APP_LOG_LEVEL_WARNING, "TURN_DIRECTION missing");
  }

  tuple = dict_find(iter, MESSAGE_KEY_DISTANCE_TO_TURN);
  if (tuple) {
    s_distance_to_turn = tuple->value->int32;
    APP_LOG(APP_LOG_LEVEL_INFO, "DISTANCE_TO_TURN=%ld", (long)s_distance_to_turn);
  }

  tuple = dict_find(iter, MESSAGE_KEY_DISTANCE_REMAINING);
  if (tuple) {
    s_distance_remaining = tuple->value->int32;
    APP_LOG(APP_LOG_LEVEL_INFO, "DISTANCE_REMAINING=%ld", (long)s_distance_remaining);
  }

  tuple = dict_find(iter, MESSAGE_KEY_STREET_NAME);
  if (tuple) {
    strncpy(s_street_name, tuple->value->cstring, sizeof(s_street_name) - 1);
    s_street_name[sizeof(s_street_name) - 1] = '\0';
    APP_LOG(APP_LOG_LEVEL_INFO, "STREET_NAME=%s", s_street_name);
  }

  tuple = dict_find(iter, MESSAGE_KEY_NUM_ROUTE_POINTS);
  if (tuple) {
    s_num_route_points = tuple->value->uint8;
    if (s_num_route_points > MAX_ROUTE_POINTS) {
      s_num_route_points = MAX_ROUTE_POINTS;
    }
    APP_LOG(APP_LOG_LEVEL_INFO, "NUM_ROUTE_POINTS=%d", s_num_route_points);
  }

  tuple = dict_find(iter, MESSAGE_KEY_ROUTE_POINTS);
  if (tuple && tuple->length <= sizeof(s_route_points)) {
    memcpy(s_route_points, tuple->value->data, tuple->length);
    APP_LOG(APP_LOG_LEVEL_INFO, "ROUTE_POINTS len=%d", tuple->length);
  }

  tuple = dict_find(iter, MESSAGE_KEY_CURRENT_LOC_INDEX);
  if (tuple) {
    s_current_loc_index = tuple->value->uint8;
    APP_LOG(APP_LOG_LEVEL_INFO, "CURRENT_LOC_INDEX=%d", s_current_loc_index);
  }

  update_text_layers();
  layer_mark_dirty(s_map_layer);
}

static void select_click_handler(ClickRecognizerRef recognizer, void *context) {
  s_map_visible = !s_map_visible;
  layout_ui();
  layer_mark_dirty(s_map_layer);
}

static void click_config_provider(void *context) {
  window_single_click_subscribe(BUTTON_ID_SELECT, select_click_handler);
}

static void window_load(Window *window) {
  Layer *window_layer = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(window_layer);

  window_set_background_color(window, GColorBlack);

  s_map_layer = layer_create(bounds);
  layer_set_update_proc(s_map_layer, map_layer_update_proc);
  layer_add_child(window_layer, s_map_layer);

  s_turn_layer = text_layer_create(GRect(0, 0, 40, 30));
  text_layer_set_background_color(s_turn_layer, GColorClear);
  text_layer_set_text_color(s_turn_layer, GColorWhite);
  layer_add_child(window_layer, text_layer_get_layer(s_turn_layer));

  s_distance_layer = text_layer_create(GRect(40, 0, bounds.size.w - 40, 30));
  text_layer_set_background_color(s_distance_layer, GColorClear);
  text_layer_set_text_color(s_distance_layer, GColorWhite);
  layer_add_child(window_layer, text_layer_get_layer(s_distance_layer));

  s_remaining_layer = text_layer_create(GRect(0, 0, bounds.size.w, 20));
  text_layer_set_background_color(s_remaining_layer, GColorClear);
  text_layer_set_text_color(s_remaining_layer, GColorWhite);
  layer_add_child(window_layer, text_layer_get_layer(s_remaining_layer));

  s_street_layer = text_layer_create(GRect(0, 0, bounds.size.w, 20));
  text_layer_set_background_color(s_street_layer, GColorClear);
  text_layer_set_text_color(s_street_layer, GColorWhite);
  layer_add_child(window_layer, text_layer_get_layer(s_street_layer));

  layout_ui();
  update_text_layers();

  app_message_register_inbox_received(inbox_received_callback);
  app_message_open(256, 64);
}

static void window_unload(Window *window) {
  text_layer_destroy(s_street_layer);
  text_layer_destroy(s_remaining_layer);
  text_layer_destroy(s_distance_layer);
  text_layer_destroy(s_turn_layer);
  layer_destroy(s_map_layer);
}

static void init() {
  s_window = window_create();
  window_set_window_handlers(s_window, (WindowHandlers) {
    .load = window_load,
    .unload = window_unload,
  });
  window_set_click_config_provider(s_window, click_config_provider);
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
