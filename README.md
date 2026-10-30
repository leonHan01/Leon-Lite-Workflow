# Hello Service

一个使用 Java 21 和 Spring Boot 4.1.2 的简单 HTTP 服务。

## 运行

需要安装 JDK 21 和 Maven。确认 `java -version` 显示 Java 21，然后在项目目录执行：

如果 macOS 的默认 Java 版本不是 21，可以先执行：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
```

启动服务：

```bash
mvn spring-boot:run
```

在另一个终端请求接口：

```bash
curl http://localhost:8080/hello
```

响应内容为 `你好`。

## 打包

```bash
mvn package
java -Xms128m -Xmx512m -jar target/hello-service-0.0.1-SNAPSHOT.jar
```

应用默认监听 8080 端口，可通过 `--server.port=端口号` 修改。
