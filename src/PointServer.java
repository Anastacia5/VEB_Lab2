import com.fastcgi.FCGIInterface;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

public class PointServer {
    private static final List<Result> HISTORY = new ArrayList<>();
    private static final int MAX_BODY_BYTES = 8192;
    private static final int MAX_NUMBER_LENGTH = 64;
    private static final Pattern DECIMAL =
            Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)");
    private static final Set<String> ALLOWED_PARAMS = Set.of("x", "y", "r", "action");
    private static final List<BigDecimal> ALLOWED_R = List.of(
            new BigDecimal("1"), new BigDecimal("1.5"), new BigDecimal("2"),
            new BigDecimal("2.5"), new BigDecimal("3"));
    private static final ZoneId TIME_ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private static class Result {
        final BigDecimal x;
        final BigDecimal y;
        final BigDecimal r;
        final boolean hit;
        final String time;

        Result(BigDecimal x, BigDecimal y, BigDecimal r, boolean hit, String time) {
            this.x = x;
            this.y = y;
            this.r = r;
            this.hit = hit;
            this.time = time;
        }

        String toJson() {
            return "{"
                    + "\"x\":" + jsonString(x.toPlainString()) + ","
                    + "\"y\":" + jsonString(y.toPlainString()) + ","
                    + "\"r\":" + jsonString(r.toPlainString()) + ","
                    + "\"hit\":" + hit + ","
                    + "\"time\":" + jsonString(time)
                    + "}";
        }
    }

    private static class RequestException extends IllegalArgumentException {
        final int status;

        RequestException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static void main(String[] args) {
        FCGIInterface fcgi = new FCGIInterface();

        while (fcgi.FCGIaccept() >= 0) {
            long started = System.nanoTime();
            int status = 200;
            String error = null;

            try {
                Properties request = FCGIInterface.request.params;
                if (!"POST".equals(request.getProperty("REQUEST_METHOD"))) {
                    throw new RequestException(405, "Разрешён только метод POST.");
                }

                String contentType = request.getProperty("CONTENT_TYPE", "");
                if (!"application/x-www-form-urlencoded".equalsIgnoreCase(
                        contentType.split(";", 2)[0].trim())) {
                    throw new RequestException(415,
                            "Ожидается Content-Type: application/x-www-form-urlencoded.");
                }

                int length;
                try {
                    length = Integer.parseInt(request.getProperty("CONTENT_LENGTH", "0"));
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("Некорректный размер запроса.");
                }
                if (length < 0) {
                    throw new IllegalArgumentException("Некорректный размер запроса.");
                }
                if (length > MAX_BODY_BYTES) {
                    throw new RequestException(413, "Размер запроса превышает 8192 байта.");
                }

                String body = new String(readExactly(FCGIInterface.request.inStream, length),
                        StandardCharsets.UTF_8);
                Map<String, List<String>> params = parseForm(body);
                String action = single(params, "action");
                if (action != null) {
                    if (!"history".equals(action) || params.size() != 1) {
                        throw new IllegalArgumentException("Некорректное действие.");
                    }
                } else {
                    processPointRequest(params);
                }
            } catch (RequestException exception) {
                status = exception.status;
                error = exception.getMessage();
            } catch (IllegalArgumentException | IOException exception) {
                status = 400;
                error = exception.getMessage();
            } catch (Exception exception) {
                status = 500;
                error = "Внутренняя ошибка сервера.";
                exception.printStackTrace(System.err);
            }

            try {
                byte[] response = jsonResponse(error, started).getBytes(StandardCharsets.UTF_8);
                String headers = "Status: " + status + " " + reasonPhrase(status) + "\r\n"
                        + "Content-Type: application/json; charset=utf-8\r\n"
                        + "Content-Length: " + response.length + "\r\n"
                        + "Cache-Control: no-store\r\n"
                        + (status == 405 ? "Allow: POST\r\n" : "")
                        + "\r\n";
                OutputStream output = FCGIInterface.request.outStream;
                output.write(headers.getBytes(StandardCharsets.US_ASCII));
                output.write(response);
                output.flush();
            } catch (IOException exception) {
                exception.printStackTrace(System.err);
            }
        }
    }

    private static void processPointRequest(Map<String, List<String>> params) {
        BigDecimal x = parseDecimal(single(params, "x"), "X");
        BigDecimal r = parseDecimal(single(params, "r"), "R");

        if (x.compareTo(BigDecimal.valueOf(-3)) < 0
                || x.compareTo(BigDecimal.valueOf(3)) > 0) {
            throw new IllegalArgumentException("X должен находиться в диапазоне от -3 до 3.");
        }
        if (ALLOWED_R.stream().noneMatch(allowed -> r.compareTo(allowed) == 0)) {
            throw new IllegalArgumentException(
                    "R должен быть одним из значений 1, 1.5, 2, 2.5, 3.");
        }

        List<String> yValues = params.get("y");
        if (yValues == null || yValues.isEmpty() || yValues.size() > 9) {
            throw new IllegalArgumentException("Выберите от 1 до 9 значений Y.");
        }

        List<Result> pending = new ArrayList<>();
        Set<Integer> seenY = new HashSet<>();
        String now = currentTime();
        for (String yText : yValues) {
            BigDecimal y = parseDecimal(yText, "Y");
            if (y.compareTo(BigDecimal.valueOf(-4)) < 0
                    || y.compareTo(BigDecimal.valueOf(4)) > 0
                    || y.stripTrailingZeros().scale() > 0) {
                throw new IllegalArgumentException("Y должен быть целым числом от -4 до 4.");
            }
            if (!seenY.add(y.intValueExact())) {
                throw new IllegalArgumentException("Значения Y не должны повторяться.");
            }
            pending.add(new Result(x, y, r, isInside(x, y, r), now));
        }
        HISTORY.addAll(pending);
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(bytes, offset, length - offset);
            if (count <= 0) {
                throw new IOException("Запрос оборвался до получения всех данных.");
            }
            offset += count;
        }
        return bytes;
    }

    private static Map<String, List<String>> parseForm(String body) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        if (body.isEmpty()) {
            return params;
        }
        for (String pair : body.split("&", -1)) {
            String[] parts = pair.split("=", 2);
            String key;
            String value;
            try {
                key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                value = parts.length > 1
                        ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Некорректная кодировка параметров.");
            }
            if (!ALLOWED_PARAMS.contains(key)) {
                throw new IllegalArgumentException("Неизвестный параметр запроса.");
            }
            params.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
            if (!"y".equals(key) && params.get(key).size() > 1) {
                throw new IllegalArgumentException("Параметр " + key + " передан несколько раз.");
            }
        }
        return params;
    }

    private static String single(Map<String, List<String>> params, String key) {
        List<String> values = params.get(key);
        return values == null ? null : values.get(0);
    }

    private static BigDecimal parseDecimal(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Не передано значение " + name + ".");
        }
        if (value.length() > MAX_NUMBER_LENGTH || !DECIMAL.matcher(value).matches()) {
            throw new IllegalArgumentException(name
                    + " должно быть десятичным числом длиной не более 64 символов.");
        }
        return new BigDecimal(value);
    }

    private static boolean isInside(BigDecimal x, BigDecimal y, BigDecimal r) {
        BigDecimal zero = BigDecimal.ZERO;
        BigDecimal halfR = r.divide(BigDecimal.valueOf(2));

        boolean triangle = x.compareTo(zero) >= 0
                && y.compareTo(zero) >= 0
                && x.add(y.multiply(BigDecimal.valueOf(2))).compareTo(r) <= 0;
        boolean circle = x.compareTo(zero) <= 0
                && y.compareTo(zero) <= 0
                && x.multiply(x).add(y.multiply(y))
                        .compareTo(halfR.multiply(halfR)) <= 0;
        boolean rectangle = x.compareTo(zero) >= 0
                && x.compareTo(r) <= 0
                && y.compareTo(zero) <= 0
                && y.compareTo(halfR.negate()) >= 0;

        return triangle || circle || rectangle;
    }

    private static String currentTime() {
        return ZonedDateTime.now(TIME_ZONE).format(TIME_FORMAT);
    }

    private static String jsonResponse(String error, long started) {
        StringBuilder json = new StringBuilder("{");
        if (error != null) {
            json.append("\"error\":").append(jsonString(error)).append(',');
        }
        json.append("\"results\":[");
        for (int i = 0; i < HISTORY.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(HISTORY.get(i).toJson());
        }
        json.append("],\"currentTime\":").append(jsonString(currentTime()));
        json.append(",\"executionTime\":").append((System.nanoTime() - started) / 1_000_000.0);
        return json.append('}').toString();
    }

    private static String reasonPhrase(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 405 -> "Method Not Allowed";
            case 413 -> "Payload Too Large";
            case 415 -> "Unsupported Media Type";
            default -> "Internal Server Error";
        };
    }

    private static String jsonString(String value) {
        StringBuilder json = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append(String.format("\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        return json.append('"').toString();
    }
} 