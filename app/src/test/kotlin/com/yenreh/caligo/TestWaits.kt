package com.yenreh.caligo

/**
 * How long a test waits for work that runs off its thread (view models on
 * other dispatchers, OkHttp, the disk, MapCompose's own threads) before it
 * fails. Generous: CI runners are several times slower than a laptop and
 * lint there runs beside the tests. A wait whose condition holds returns at
 * once, so passing tests take no longer. Only for waits on something that
 * must happen, never for showing that something does not.
 */
const val WAIT_TIMEOUT_MS = 60_000L
