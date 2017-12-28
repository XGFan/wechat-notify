package com.test4x.app.notify

interface Repo {
    fun put(key: String, value: String)

    fun get(key: String): String?
}