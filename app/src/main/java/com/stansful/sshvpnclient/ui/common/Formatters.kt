package com.stansful.sshvpnclient.ui.common

import java.text.DateFormat
import java.util.Date

fun formatDateTime(timestamp: Long): String {
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
}
