# Intentionally left without any hand-written kotlinx.serialization rules - this app
# depends on kotlinx-serialization-json, which ships its own consumer ProGuard rules
# that R8 picks up automatically. The point of this file being empty is to prove those
# bundled rules are sufficient on their own for the plugin's default JSON converter.
