package com.test4x.app.notify

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import me.chanjar.weixin.cp.api.impl.WxCpServiceImpl
import me.chanjar.weixin.cp.bean.WxCpMessage
import me.chanjar.weixin.cp.config.WxCpInMemoryConfigStorage
import org.apache.commons.lang3.RandomStringUtils
import org.jtwig.JtwigModel
import org.jtwig.JtwigTemplate
import org.slf4j.LoggerFactory
import spark.Filter
import spark.Spark
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap


fun main(args: Array<String>) {
    val logger = LoggerFactory.getLogger("notify")

    val config = Config(File("application.properties"))

    val wxCpConfigStorage = WxCpInMemoryConfigStorage()
    wxCpConfigStorage.corpId = config.get("corpId")
    wxCpConfigStorage.agentId = config.get("agentId")?.toInt()
    wxCpConfigStorage.corpSecret = config.get("agentSecret")

    val wxCpService = WxCpServiceImpl()
    wxCpService.wxCpConfigStorage = wxCpConfigStorage

    val redisMode = (config.get("redis.enable") ?: "false").toBoolean()
    val repo = if (redisMode) {
        val host = config.get("redis.host") ?: "127.0.0.1"
        val port = config.get("redis.port")?.toInt() ?: 6379
        val pwd = config.get("redis.password")

        val builder = RedisURI.Builder.redis(host, port)
        if (pwd != null) {
            builder.withPassword(pwd)
        }
        val client = RedisClient.create(builder.build())
        val connection = client.connect()
        val sync = connection.sync()

        object : Repo {
            override fun put(key: String, value: String) {
                sync.set(key, value)
            }

            override fun get(key: String): String? = sync[key]
        }
    } else {
        val map = ConcurrentHashMap<String, String>()
        object : Repo {
            override fun put(key: String, value: String) {
                map[key] = value
            }

            override fun get(key: String): String? = map[key]
        }
    }

    val baseUrl = config.get("baseUrl")

    val detailView = JtwigTemplate.classpathTemplate("templates/wechat.twig")

    Spark.port(config.get("server.port")?.toInt() ?: 8080)
    Spark.after(Filter { req, res ->
        res.header("Content-Encoding", "gzip")
    })

    Spark.post("wechat") { req, res ->
        val title = req.queryParams("title") ?: return@post "fail"
        val content = req.queryParams("content") ?: return@post "fail"
        val user = req.queryParams("user") ?: return@post "fail"
        val randomId = RandomStringUtils.randomAlphanumeric(16)
        val message = WxCpMessage.TEXTCARD()
                .title(title)
                .description(content)
                .toUser(user)
                .url("$baseUrl/wechat/$randomId")
                .build()
        if (wxCpService.messageSend(message).errCode == 0) {
            repo.put("$randomId|title", title)
            repo.put("$randomId|content", content)
            logger.info("{}|{}|{}", randomId, title, content)
            "ok"
        } else {
            "fail"
        }
    }
    Spark.get("wechat/:randomId") { req, res ->
        val randomId = req.params(":randomId")
        val title = repo.get("$randomId|title") ?: return@get "Error"
        val content = repo.get("$randomId|content") ?: return@get "Error"
        val model = JtwigModel.newModel().with("title", title).with("content", content)
        detailView.render(model)
    }

    Spark.post("mail2wechat") { req, res ->
        val title = req.queryParams("subject")
        val content = req.queryParams("body-plain")
        val toUser = req.queryParams("recipient").split("@")[0]
        val randomId = RandomStringUtils.randomAlphanumeric(16)
        val message = WxCpMessage.TEXTCARD()
                .title(title)
                .description(content)
                .toUser(toUser)
                .url("$baseUrl/wechat/$randomId")
                .build()
        if (wxCpService.messageSend(message).errCode == 0) {
            repo.put("$randomId|title", title)
            repo.put("$randomId|content", content)
            logger.info("{}|{}|{}", randomId, title, content)
            "ok"
        } else {
            "fail"
        }
    }
}

fun randomString(len: Int): String {
    val random = Random()
    val stringBuilder = StringBuilder()
    repeat(len) {
        val i = random.nextInt(62)
        val x = when {
            i < 10 -> (i + 48).toChar()
            i < 36 -> (i + 55).toChar()
            else -> (i + 61).toChar()
        }
        stringBuilder.append(x)
    }
    return stringBuilder.toString()
}

fun randomNumString(len: Int): String {
    val random = Random()
    val stringBuilder = StringBuilder()
    repeat(len) {
        val i = random.nextInt(10)
        stringBuilder.append(i + 48)
    }
    return stringBuilder.toString()
}

class Config {
    val properties: Properties = Properties()

    fun get(key: String): String? {
        return properties[key]?.toString() ?: System.getenv(key)
    }

    constructor(file: File) {
        if (!file.exists()) {
            //
        } else {
            properties.load(file.inputStream())
        }
    }


}

interface Repo {
    fun put(key: String, value: String)

    fun get(key: String): String?
}
