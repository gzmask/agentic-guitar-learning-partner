/* Csound 6 can report instrument errors while csoundPerform returns the same
 * positive end-of-score code as success. Its public message-buffer API is an
 * unbounded queue. Latch severity instead: no allocation, printing, locks,
 * string copying, or callback into Jolt on the performance thread.
 * The context lives from before csoundCreate until after csoundDestroy. */
#include <csound.h>
#include <msg_attr.h>
#include <sndfile.h>
#include <stdatomic.h>
#include <stdlib.h>

_Static_assert(ATOMIC_INT_LOCK_FREE == 2, "A lock-free error latch is required");

typedef struct {
    atomic_int error_seen;
} LooperDiagnostics;

void *looperDiagnosticsCreate(void) {
    LooperDiagnostics *state = malloc(sizeof(*state));
    if (state != NULL) {
        atomic_init(&state->error_seen, 0);
    }
    return state;
}

static void receive_message(CSOUND *csound, int attributes, const char *message) {
    (void)message;
    LooperDiagnostics *state = csoundGetHostData(csound);
    if (state != NULL && (attributes & CSOUNDMSG_TYPE_MASK) == CSOUNDMSG_ERROR) {
        atomic_store_explicit(&state->error_seen, 1, memory_order_relaxed);
    }
}

void looperDiagnosticsAttach(CSOUND *csound) {
    csoundSetMessageStringCallback(csound, receive_message);
}

void looperDiagnosticsReset(void *context) {
    LooperDiagnostics *state = context;
    atomic_store_explicit(&state->error_seen, 0, memory_order_relaxed);
}

int looperDiagnosticsFailed(void *context) {
    LooperDiagnostics *state = context;
    return atomic_load_explicit(&state->error_seen, memory_order_relaxed);
}

void looperDiagnosticsFree(void *context) {
    free(context);
}

/* Startup-only bulk load into an already allocated table. The selected Csound
 * build was version-gated to double MYFLT, so sf_read_double writes directly.
 * Return codes are deliberately small/stable for structured host errors. */
int looperLoadMonoTable(CSOUND *csound, int table, const char *path,
                        int expected_frames) {
    SF_INFO info = {0};
    SNDFILE *file = sf_open(path, SFM_READ, &info);
    if (file == NULL) return 1;
    if (info.channels != 1 || info.samplerate != 44100 ||
        info.frames != expected_frames || expected_frames < 1 ||
        csoundTableLength(csound, table) != expected_frames) {
        sf_close(file);
        return 2;
    }
    double *samples = malloc((size_t)expected_frames * sizeof(*samples));
    if (samples == NULL) {
        sf_close(file);
        return 3;
    }
    sf_count_t count = sf_read_double(file, samples, expected_frames);
    int close_result = sf_close(file);
    if (count != expected_frames || close_result != 0) {
        free(samples);
        return 4;
    }
    csoundTableCopyIn(csound, table, samples);
    free(samples);
    return 0;
}
