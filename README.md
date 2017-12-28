### 微信通知

方糖的*copycat*

首先需要注册一个[企业微信](https://work.weixin.qq.com/) ，无需认证。然后新建一个应用，获取`corp_id` ,`agent_id`,`secret` 。

```bash
mvn clean package
java -jar the-jar-with-dependencies.jar
```

配置文件放在jar所在的目录。

```properties
corpId=your_corp_id
agentId=your_agent_id
agentSecret=your_secret

baseUrl=http://www.qq.com #外网能访问到该server的url

redis.enable=true #如果不开启就是本机的文件作为数据库，默认关闭
redis.host=127.0.0.1
redis.port=6379
redis.password=pwd #如果没有密码就移除改项

server.port=8080 #web端口
```

