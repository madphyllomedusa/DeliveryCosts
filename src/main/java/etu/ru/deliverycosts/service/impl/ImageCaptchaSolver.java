package etu.ru.deliverycosts.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Service
@Slf4j
public class ImageCaptchaSolver {

    // Ключ 2captcha
    private static final String API_KEY = "ea1af8a726fdf205180c3374320c708c";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * Отправляем картинку (base64), ждём решения и возвращаем текст капчи.
     */
    public String solveCaptcha(byte[] imageBytes) throws IOException, InterruptedException {
        // 1) Кодируем картинку в Base64
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);

        // 2) Отправляем запрос в 2captcha (method=base64, body=base64Image)
        String postBody = "key=" + urlEncode(API_KEY)
                + "&method=base64"
                + "&body=" + urlEncode(base64Image)
                + "&json=1";

        HttpRequest requestIn = HttpRequest.newBuilder()
                .uri(URI.create("https://2captcha.com/in.php"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(postBody))
                .build();

        HttpResponse<String> responseIn = httpClient.send(requestIn, HttpResponse.BodyHandlers.ofString());
        String responseBodyIn = responseIn.body();
        log.info("2captcha IN response: {}", responseBodyIn);

        // Ищем captchaId (request) внутри JSON: {"status":1,"request":"123456789"}
        String captchaId = parseCaptchaId(responseBodyIn);
        if (captchaId == null) {
            throw new RuntimeException("Не удалось получить captchaId: " + responseBodyIn);
        }

        // 3) Циклически опрашиваем решение
        while (true) {
            Thread.sleep(5000);

            String urlRes = String.format("https://2captcha.com/res.php?key=%s&action=get&id=%s&json=1", API_KEY, captchaId);
            HttpRequest requestRes = HttpRequest.newBuilder()
                    .uri(URI.create(urlRes))
                    .GET()
                    .build();

            HttpResponse<String> responseRes = httpClient.send(requestRes, HttpResponse.BodyHandlers.ofString());
            String responseBodyRes = responseRes.body();
            log.info("2captcha RES response: {}", responseBodyRes);

            if (responseBodyRes.contains("\"status\":1")) {
                // Решена успешно
                String text = parseCaptchaSolution(responseBodyRes);
                log.info("Капча решена: {}", text);
                return text;
            }
            // Если не CAPCHA_NOT_READY — значит ошибка
            if (!responseBodyRes.contains("CAPCHA_NOT_READY")) {
                throw new RuntimeException("Ошибка решения капчи: " + responseBodyRes);
            }
            log.info("Капча ещё не готова, ждём...");
        }
    }

    // Примитивное извлечение captchaId
    private String parseCaptchaId(String jsonIn) {
        if (!jsonIn.contains("\"status\":1")) return null;
        int idx = jsonIn.indexOf("\"request\":\"");
        if (idx < 0) return null;
        int start = idx + "\"request\":\"".length();
        int end = jsonIn.indexOf("\"", start);
        if (end < 0) return null;
        return jsonIn.substring(start, end);
    }

    // Примитивное извлечение решения капчи (request) из ответа {"status":1,"request":"qwerty"}
    private String parseCaptchaSolution(String jsonRes) {
        int idx = jsonRes.indexOf("\"request\":\"");
        if (idx < 0) return null;
        int start = idx + "\"request\":\"".length();
        int end = jsonRes.indexOf("\"", start);
        if (end < 0) return null;
        return jsonRes.substring(start, end);
    }

    private String urlEncode(String text) {
        return java.net.URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
