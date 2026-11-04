package fish.nexa.ui

import fish.nexa.crypto.WeakKdfError
import fish.nexa.net.ApiError

/** A sentence for whatever went wrong (web/src/lib/errors.ts describeError). */
fun describeError(err: Throwable?, fallback: String? = null): String {
    if (err is WeakKdfError) return t("auth.theServerAskedToProtectYourPassword")
    if (err is ReadableError) return err.message ?: fallback ?: t("modals.confirm.somethingWentWrong")
    if (err is ApiError) {
        when (err.code) {
            "network" -> return t("offline.couldNotReachTheServer")
            "timeout" -> return t("errors.theServerTookTooLong")
            "aborted" -> return t("errors.thatWasCancelledBeforeItFinished")
            "bad_response" -> return t("errors.theServerSAnswerMadeNo")
            "rate_limited" -> return t("errors.youAreDoingThatTooQuickly")
            "too_many_uploads" -> return t("errors.tooManyUploads")
            "upload_quota" -> return t("errors.uploadQuota")
            "no_space" -> return t("errors.noSpace")
            "storage_budget" -> return t("errors.storageBudget")
        }
        if (!err.code.startsWith("http_") && !err.message.isNullOrBlank()) return err.message!!
        return fallback ?: err.message ?: t("modals.confirm.somethingWentWrong")
    }
    return fallback ?: t("modals.confirm.somethingWentWrong")
}

/** An error whose message was written for the person reading it. */
class ReadableError(message: String) : Exception(message)
