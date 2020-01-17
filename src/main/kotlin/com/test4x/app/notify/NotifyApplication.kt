package com.test4x.app.notify

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import me.chanjar.weixin.cp.api.impl.WxCpServiceImpl
import me.chanjar.weixin.cp.bean.WxCpMessage
import me.chanjar.weixin.cp.config.WxCpInMemoryConfigStorage
import org.jtwig.JtwigModel
import org.jtwig.JtwigTemplate
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spark.Filter
import spark.Spark
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class NotifyApplication(private val config: Config) {

    private val logger: Logger = LoggerFactory.getLogger("notify")

    private val wxCpService = WxCpServiceImpl().apply {
        wxCpConfigStorage = WxCpInMemoryConfigStorage().apply {
            corpId = config.get("corpId")
            agentId = config.get("agentId")?.toInt()
            corpSecret = config.get("agentSecret")
        }
    }

    private val repo = if ((config.get("redis.enable") ?: "false").toBoolean()) {
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
        object : Repo {
            val map = ConcurrentHashMap<String, String>()
            override fun put(key: String, value: String) {
                map[key] = value
            }

            override fun get(key: String): String? = map[key]
        }
    }

    private val baseUrl = config.get("baseUrl")

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            NotifyApplication(Config(File("application.properties"))).run()
        }
    }

    fun run() {
        val detailView = JtwigTemplate.classpathTemplate("templates/wechat.twig")
        Spark.port(config.get("server.port")?.toInt() ?: 8080)
        Spark.after(Filter { _, res ->
            res.header("Content-Encoding", "gzip")
        })

        Spark.get("wechat/:randomId") { req, res ->
            val randomId = req.params(":randomId")
            val title = repo.get("$randomId|title") ?: return@get "Error"
            val content = repo.get("$randomId|content") ?: return@get "Error"
            val model = JtwigModel.newModel().with("title", title).with("content", content)
            detailView.render(model)
        }

        Spark.post("wechat") { req, res ->
            val title = req.queryParams("title") ?: return@post "fail"
            val content = req.queryParams("content") ?: return@post "fail"
            val toUser = req.queryParams("user") ?: return@post "fail"
            sentWechat(title, content, toUser)
        }

        Spark.post("mail2wechat") { req, res ->
            val title = req.queryParams("subject")
            val content = req.queryParams("body-plain")
            val toUser = req.queryParams("recipient").split("@")[0]
            sentWechat(title, content, toUser)
        }
    }

    private fun sentWechat(title: String, content: String, user: String): String {
        val randomId = randomString(16)
        val message = WxCpMessage.TEXTCARD()
            .title(title)
            .description(content)
            .toUser(user)
            .url("$baseUrl/wechat/$randomId")
            .build()
        return if (wxCpService.messageSend(message).errCode == 0) {
            repo.put("$randomId|title", title)
            repo.put("$randomId|content", content)
            logger.info("{}|{}|{}", randomId, title, content)
            "ok"
        } else {
            "fail"
        }
    }

    private fun randomString(len: Int): String {
        val list = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        val random = Random()
        val stringBuilder = StringBuilder()
        repeat(len) {
            val i = random.nextInt(list.size)
            stringBuilder.append(list[i])
        }
        return stringBuilder.toString()
    }

    class Config(file: File) {
        private val properties: Properties = Properties()

        fun get(key: String): String? {
            return properties[key]?.toString() ?: System.getenv(key)
        }

        init {
            if (file.exists()) {
                properties.load(file.inputStream())
            }
        }
    }

    interface Repo {
        fun put(key: String, value: String)

        fun get(key: String): String?
    }
}