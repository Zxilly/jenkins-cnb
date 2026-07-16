package dev.zxilly.jenkins.cnb.config

enum class CnbStatusReportingMode {
    DISABLED,
    COMMIT_ANNOTATION,
    PULL_REQUEST_COMMENT,
    BOTH,
}
