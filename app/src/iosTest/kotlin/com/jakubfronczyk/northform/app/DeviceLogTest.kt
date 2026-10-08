package com.jakubfronczyk.northform.app

import kotlin.test.Test

/**
 * Writes one line per category to the unified log. The simulator's log lands in the Mac's log store, so
 * the message text can be checked after the run without the device:
 * `/usr/bin/log show --last 2m --predicate 'subsystem == "com.jakubfronczyk.northform"'`
 * A `<compose failure>` body there means the os_log format string is not inside the binary (oslog.def).
 */
class DeviceLogTest {
    @Test
    fun writes_one_readable_line_per_category() {
        DeviceLog.app.notice("DeviceLogTest app line")
        DeviceLog.run.notice("DeviceLogTest run line")
        DeviceLog.location.notice("DeviceLogTest location line")
        DeviceLog.polar.notice("DeviceLogTest polar line")
    }
}
