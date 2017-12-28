package com.test4x.app.notify

import me.chanjar.weixin.cp.api.impl.WxCpServiceImpl
import me.chanjar.weixin.cp.bean.WxCpMessage
import me.chanjar.weixin.cp.config.WxCpInMemoryConfigStorage
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.RandomStringUtils
import org.jtwig.JtwigModel
import org.jtwig.JtwigTemplate
import org.mapdb.DBMaker
import org.mapdb.Serializer
import org.slf4j.LoggerFactory
import redis.clients.jedis.Jedis
import spark.Filter
import spark.Spark
import java.io.File
import java.util.*


fun main(args: Array<String>) {
    val logger = LoggerFactory.getLogger("notify")

    val properties = Properties()
    val configFile = File("application.properties")
    try {
        properties.load(configFile.inputStream())
    } catch (e: Exception) {
        println("application.properties not found")
        val exampleConfig = ClassLoader.getSystemResourceAsStream("application.properties.example")
        FileUtils.copyInputStreamToFile(exampleConfig, File("application.properties.example"))
        System.exit(-1)
    }

    val wxCpConfigStorage = WxCpInMemoryConfigStorage()
    wxCpConfigStorage.corpId = properties.getProperty("corpId")
    wxCpConfigStorage.agentId = properties.getProperty("agentId").toInt()
    wxCpConfigStorage.corpSecret = properties.getProperty("agentSecret")

    val wxCpService = WxCpServiceImpl()
    wxCpService.wxCpConfigStorage = wxCpConfigStorage

    val redisMode = properties.getProperty("redis.enable", "false").toBoolean()

    val repo = if (redisMode) {
        val host = properties.getProperty("redis.host", "127.0.0.1")
        val port = properties.getProperty("redis.port", "6379").toInt()
        val pwd = properties.getProperty("redis.password")
        val jedis = Jedis(host, port)
        if (pwd != null) {
            jedis.auth(pwd)
        }
        object : Repo {
            override fun put(key: String, value: String) {
                jedis.set(key, value)
            }

            override fun get(key: String): String? = jedis[key]
        }
    } else {
        val db = DBMaker
                .fileDB("notify.db")
                .fileMmapEnable()
                .transactionEnable()
                .make()
        val map = db.hashMap("map", Serializer.STRING, Serializer.STRING).createOrOpen()

        object : Repo {
            override fun put(key: String, value: String) {
                map.put(key, value)
                db.commit()
            }

            override fun get(key: String): String? = map[key]
        }
    }

    val baseUrl = properties.getProperty("baseUrl")

    val detailView = JtwigTemplate.classpathTemplate("templates/wechat.twig")

    Spark.port(properties.getProperty("server.port", "8080").toInt())
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
            repo.put(randomId + "|title", title)
            repo.put(randomId + "|content", content)
            logger.info("{}|{}|{}", randomId, title, content)
            "ok"
        } else {
            "fail"
        }
    }
    Spark.get("wechat/:randomId") { req, res ->
        val randomId = req.params(":randomId")
        val title = repo.get(randomId + "|title") ?: return@get "Error"
        val content = repo.get(randomId + "|content") ?: return@get "Error"
        val model = JtwigModel.newModel().with("title", title).with("content", content)
        detailView.render(model)
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
