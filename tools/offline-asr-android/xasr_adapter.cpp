// Compile the exact upstream implementation unchanged in this translation unit.
// Its context remains private; only attach GGML's public cancellation callback.
#include "xasr.cpp"
extern "C" void vime_xasr_set_abort(xasr_context *ctx, ggml_abort_callback callback, void *data) {
    ggml_backend_cpu_set_abort_callback(ctx->backend_cpu, callback, data);
}
