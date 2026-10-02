package server

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

// Модели данных для общения с DeepSeek API
@Serializable
data class DeepSeekMessage(val role: String, val content: String)

@Serializable
data class DeepSeekRequest(val model: String, val messages: List<DeepSeekMessage>)

// Глобальное хранилище истории переписки (в памяти сервера)
val chatHistory = mutableListOf<DeepSeekMessage>()

// Токен DeepSeek берется из переменных окружения хостинга ради безопасности
val DEEPSEEK_API_KEY = System.getenv("DEEPSEEK_API_KEY") ?: "YOUR_FALLBACK_KEY"

fun main() {
    // Добавляем системный промпт, задающий роль нейросети
    if (chatHistory.isEmpty()) {
        chatHistory.add(DeepSeekMessage("system", "Ты — полезный ассистент."))
    }

    embeddedServer(Netty, port = System.getenv("PORT")?.toInt() ?: 8080) {
        install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
            json()
        }

        val client = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        routing {
            // Главная страница в браузере с интерфейсом чата
            get("/") {
                call.respondHtml {
                    head { 
                        title("Kotlin DeepSeek Chat")
                        style {
                            +"""
                            body { font-family: sans-serif; max-width: 600px; margin: 50px auto; padding: 10px; }
                            .chat-box { border: 1px solid #ccc; height: 400px; overflow-y: scroll; padding: 10px; margin-bottom: 10px; }
                            .user { color: blue; margin-bottom: 5px; }
                            .bot { color: green; margin-bottom: 5px; }
                            input[type="text"] { width: 80%; padding: 10px; }
                            input[type="submit"] { width: 18%; padding: 10px; }
                            """.trimIndent()
                        }
                    }
                    body {
                        h2 { +"Чат с DeepSeek (с контекстом)" }
                        div("chat-box") {
                            // Выводим всю историю, кроме системного промпта
                            chatHistory.drop(1).forEach { msg ->
                                div(if (msg.role == "user") "user" else "bot") {
                                    b { +(if (msg.role == "user") "Вы: " else "DeepSeek: ") }
                                    +msg.content
                                }
                            }
                        }
                        form(action = "/send", method = FormMethod.post) {
                            inputText(name = "message") { 
                                placeholder = "Введите сообщение..."
                                attributes["required"] = "true"
                                attributes["autocomplete"] = "off"
                            }
                            submitInput { value = "Отправить" }
                        }
                    }
                }
            }

            // Обработка отправки нового сообщения
            post("/send") {
                val parameters = call.receiveParameters()
                val userText = parameters["message"] ?: ""

                if (userText.isNotBlank()) {
                    // 1. Запоминаем реплику пользователя в контекст
                    chatHistory.add(DeepSeekMessage("user", userText))

                    try {
                        // 2. Отправляем ВСЮ историю сообщений в DeepSeek
                        val response: HttpResponse = client.post("https://api.deepseek.com/v1/chat/completions") {
                            header(HttpHeaders.Authorization, "Bearer $DEEPSEEK_API_KEY")
                            header(HttpHeaders.ContentType, ContentType.Application.Json)
                            // Используем актуальную легкую модель deepseek-flash или флагманскую deepseek-v4-pro
                            setBody(DeepSeekRequest(model = "deepseek-flash", messages = chatHistory))
                        }

                        if (response.status == HttpStatusCode.OK) {
                            val responseBody = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                            val botResponse = responseBody["choices"]?.jsonArray?.get(0)
                                ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content ?: "Ошибка разбора ответа"
                            
                            // 3. Запоминаем ответ нейросети в контекст
                            chatHistory.add(DeepSeekMessage("assistant", botResponse))
                        } else {
                            chatHistory.add(DeepSeekMessage("assistant", "Ошибка API: ${response.status}"))
                        }
                    } catch (e: Exception) {
                        chatHistory.add(DeepSeekMessage("assistant", "Произошла ошибка: ${e.message}"))
                    }
                }
                // Перезагружаем страницу, чтобы отобразить новые сообщения
                call.respondRedirect("/")
            }
        }
    }.start(wait = true)
}
