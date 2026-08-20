// Real JNI bridge into llama.cpp's public C API (include/llama.h, fetched
// for real at build time - see CMakeLists.txt). No mocked/stubbed model
// behaviour anywhere in this file: every function below either calls into
// the real llama.cpp API or returns an honest error/false. There is no
// fallback path that fabricates a fake generated answer.
//
// Lifecycle mirrors llama.cpp's own examples/simple/simple.cpp:
//   llama_backend_init() -> llama_model_load_from_file() ->
//   llama_init_from_model() -> [per message: tokenize -> llama_decode loop
//   -> llama_sampler_sample per step] -> llama_free() / llama_model_free()
//   on unload.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <cstring>
#include <algorithm>

#include "llama.h"

#define LOG_TAG "BrainLlamaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_engine_mutex;
llama_model *g_model = nullptr;
llama_context *g_ctx = nullptr;
const llama_vocab *g_vocab = nullptr;
bool g_backend_initialized = false;
int g_n_ctx = 0;

// Frees whatever is currently loaded. Caller must hold g_engine_mutex.
void unload_locked() {
    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_vocab = nullptr;
    g_n_ctx = 0;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_sa_computebridge_engine_BrainNative_nativeBackendInit(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (!g_backend_initialized) {
        ggml_backend_load_all();
        llama_backend_init();
        g_backend_initialized = true;
        LOGI("llama backend initialized");
    }
    return JNI_TRUE;
}

// Loads a real GGUF model file from disk. Returns true only if
// llama_model_load_from_file and llama_init_from_model both succeed -
// any failure is surfaced honestly to Kotlin as false, never papered over.
JNIEXPORT jboolean JNICALL
Java_com_sa_computebridge_engine_BrainNative_nativeLoadModel(
        JNIEnv *env, jobject /* thiz */, jstring modelPath, jint nCtx, jint nThreads) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    std::string pathStr(path);
    env->ReleaseStringUTFChars(modelPath, path);

    unload_locked();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU-only: correct default for phones
                                    // without a verified GPU backend build.

    LOGI("Loading model from %s", pathStr.c_str());
    g_model = llama_model_load_from_file(pathStr.c_str(), model_params);
    if (g_model == nullptr) {
        LOGE("llama_model_load_from_file failed for %s", pathStr.c_str());
        return JNI_FALSE;
    }

    g_vocab = llama_model_get_vocab(g_model);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = static_cast<uint32_t>(nCtx > 0 ? nCtx : 2048);
    ctx_params.n_batch = ctx_params.n_ctx;
    ctx_params.n_threads = nThreads > 0 ? nThreads : 4;
    ctx_params.n_threads_batch = ctx_params.n_threads;

    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (g_ctx == nullptr) {
        LOGE("llama_init_from_model failed");
        llama_model_free(g_model);
        g_model = nullptr;
        g_vocab = nullptr;
        return JNI_FALSE;
    }

    g_n_ctx = static_cast<int>(ctx_params.n_ctx);
    LOGI("Model loaded. n_ctx=%d n_threads=%d", g_n_ctx, ctx_params.n_threads);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_sa_computebridge_engine_BrainNative_nativeUnloadModel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    unload_locked();
    LOGI("Model unloaded");
}

JNIEXPORT jboolean JNICALL
Java_com_sa_computebridge_engine_BrainNative_nativeIsModelLoaded(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    return (g_model != nullptr && g_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_sa_computebridge_engine_BrainNative_nativeGetContextSize(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    return g_n_ctx;
}

// Streams real generated tokens one at a time into a Kotlin callback object
// (interface BrainNative.TokenCallback, method `onToken(String): Boolean`).
// Generation stops when: the model emits a real end-of-generation token,
// maxTokens is reached, the context fills up, or the Kotlin side returns
// false from onToken (user cancelled). Returns the real stop reason as a
// short string so the Kotlin layer never has to guess.
JNIEXPORT jstring JNICALL
Java_com_sa_computebridge_engine_BrainNative_nativeGenerate(
        JNIEnv *env, jobject /* thiz */, jstring prompt, jint maxTokens,
        jfloat temperature, jfloat topP, jobject callback) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);

    if (g_model == nullptr || g_ctx == nullptr || g_vocab == nullptr) {
        return env->NewStringUTF("error: no model loaded");
    }

    // Real bug fix: g_ctx (and its KV cache) is a single process-wide
    // context that lives across every call to this function - nothing
    // was ever clearing it between messages. Message 2's prompt was
    // therefore being decoded on TOP of whatever was still sitting in
    // the KV cache from message 1 (n_cur below always restarts local
    // position accounting at 0 for the new prompt, but the cache itself
    // still held message 1's real tokens), so the model was effectively
    // still attending to the previous message's state - exactly the
    // "second message's tokens/behaviour look like they belong to the
    // first one" symptom. Clearing real KV memory here makes every
    // nativeGenerate() call start from a genuinely clean, empty cache -
    // this app never sends running conversation history in [prompt]
    // anyway (Kotlin side only sends the current message's own text), so
    // a full clear on every call is correct, not just a workaround.
    llama_memory_t mem = llama_get_memory(g_ctx);
    if (mem != nullptr) {
        llama_memory_clear(mem, true);
    }

    const char *promptChars = env->GetStringUTFChars(prompt, nullptr);
    std::string promptStr(promptChars);
    env->ReleaseStringUTFChars(prompt, promptChars);

    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)Z");
    if (onTokenMethod == nullptr) {
        return env->NewStringUTF("error: callback missing onToken(String):Boolean");
    }

    // --- Tokenize the prompt (real llama_tokenize call) ---
    const bool add_bos = true;
    int n_prompt_tokens = -llama_tokenize(
            g_vocab, promptStr.c_str(), static_cast<int32_t>(promptStr.size()),
            nullptr, 0, add_bos, true);
    if (n_prompt_tokens <= 0) {
        return env->NewStringUTF("error: tokenization failed");
    }
    std::vector<llama_token> prompt_tokens(n_prompt_tokens);
    if (llama_tokenize(g_vocab, promptStr.c_str(), static_cast<int32_t>(promptStr.size()),
                        prompt_tokens.data(), n_prompt_tokens, add_bos, true) < 0) {
        return env->NewStringUTF("error: tokenization failed");
    }

    if (n_prompt_tokens >= g_n_ctx) {
        return env->NewStringUTF("error: prompt longer than context window");
    }

    // --- Build a real sampler chain (repeat-penalty -> top-k -> top-p ->
    // temperature -> distribution) ---
    // Real bug fix: with no repetition penalty at all, a small on-device
    // model (e.g. Qwen2.5-1.5B) very often falls into a genuine sampling
    // loop - once it emits a phrase, that same phrase's tokens keep
    // scoring highest again on every following step, so it regenerates
    // the identical line over and over until maxTokens is hit, both
    // offline and when web-search context is used (the search only
    // changes the prompt/context fed in - this sampler chain runs the
    // same way regardless). llama_sampler_init_penalties looks back over
    // the last [penalty_last_n] real generated tokens and down-weights
    // ones already seen, which is the standard llama.cpp fix for this -
    // added first in the chain so it applies before top-k/top-p narrow
    // the distribution.
    const int penalty_last_n = 64;
    const float penalty_repeat = 1.1f;
    const float penalty_freq = 0.0f;
    const float penalty_present = 0.0f;

    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sampler_params);
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(
        llama_vocab_n_tokens(g_vocab),
        penalty_last_n, penalty_repeat, penalty_freq, penalty_present));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP > 0 ? topP : 0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature > 0 ? temperature : 0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // --- Feed the prompt through decode, in chunks ---
    // Real bug fix (user report - after a Kotlin-side timeout gives up
    // waiting on a slow reply, the NEXT message/reply gets stuck at
    // "Starting..." with 0 tokens forever, seemingly looping). Root
    // cause: this whole function holds g_engine_mutex for its entire
    // duration, and this used to be ONE single llama_decode() call for
    // the whole prompt - completely uninterruptible. A withTimeoutOrNull
    // on the Kotlin side only stops *waiting* on this call; it can never
    // actually stop the call itself while it's inside this one
    // monolithic decode, so a slow prefill (long prompt on a small
    // on-device model) kept running in the background, still holding
    // g_engine_mutex, long after Kotlin had already given up on it -
    // and every subsequent generate() call (the timeout's own fallback
    // reply, or simply the next message) then blocked trying to acquire
    // the same mutex, looking exactly like a silent infinite hang.
    // Feeding the prompt in bounded chunks and checking the real,
    // already-existing Kotlin-side cancel signal (the same TokenCallback
    // used for real generated tokens - an empty piece here is just a
    // heartbeat, never counted as a real token) between chunks means a
    // timeout/Stop can now actually interrupt prefill itself, not only
    // the decode-one-token-at-a-time loop below.
    std::string stopReason = "max_tokens";
    const int kPrefillChunkTokens = 256;
    const int n_prompt = static_cast<int>(prompt_tokens.size());
    int n_fed = 0;
    bool prefillCancelled = false;
    while (n_fed < n_prompt) {
        int chunkLen = std::min(kPrefillChunkTokens, n_prompt - n_fed);
        llama_batch chunkBatch = llama_batch_get_one(prompt_tokens.data() + n_fed, chunkLen);
        if (llama_decode(g_ctx, chunkBatch) != 0) {
            llama_sampler_free(sampler);
            return env->NewStringUTF("error: llama_decode failed on prompt");
        }
        n_fed += chunkLen;

        if (n_fed < n_prompt) {
            jstring heartbeat = env->NewStringUTF("");
            jboolean keepGoing = env->CallBooleanMethod(callback, onTokenMethod, heartbeat);
            env->DeleteLocalRef(heartbeat);
            if (!keepGoing) {
                prefillCancelled = true;
                break;
            }
        }
    }
    if (prefillCancelled) {
        llama_sampler_free(sampler);
        return env->NewStringUTF("cancelled");
    }

    int n_generated = 0;
    int n_cur = n_prompt_tokens;
    const int limit = maxTokens > 0 ? maxTokens : 512;
    char piece_buf[256];

    while (n_generated < limit) {
        llama_token new_token = llama_sampler_sample(sampler, g_ctx, -1);

        if (llama_vocab_is_eog(g_vocab, new_token)) {
            stopReason = "end_of_generation";
            break;
        }

        int piece_len = llama_token_to_piece(g_vocab, new_token, piece_buf, sizeof(piece_buf), 0, true);
        if (piece_len < 0) {
            stopReason = "error: token_to_piece failed";
            break;
        }
        std::string piece(piece_buf, piece_len);

        jstring jpiece = env->NewStringUTF(piece.c_str());
        jboolean keepGoing = env->CallBooleanMethod(callback, onTokenMethod, jpiece);
        env->DeleteLocalRef(jpiece);
        n_generated++;

        if (!keepGoing) {
            stopReason = "cancelled";
            break;
        }

        if (n_cur >= g_n_ctx - 1) {
            stopReason = "context_full";
            break;
        }

        llama_batch next_batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(g_ctx, next_batch) != 0) {
            stopReason = "error: llama_decode failed mid-generation";
            break;
        }
        n_cur++;
    }

    llama_sampler_free(sampler);
    return env->NewStringUTF(stopReason.c_str());
}

} // extern "C"
