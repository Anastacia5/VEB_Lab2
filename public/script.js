const canvas = document.getElementById("coordinate-plane");
const context = canvas.getContext("2d");

const form = document.getElementById("point-form");
const errorMessage = document.getElementById("error-message");
const resultsBody = document.getElementById("results-body");
const rInput = document.getElementById("r");
const executionInfo = document.getElementById("execution-info");
const submitButton = form.querySelector('button[type="submit"]');
const allowedR = [1, 1.5, 2, 2.5, 3];
const decimalPattern = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/;
let resultHistory = [];
let requestPending = false;

const center = canvas.width / 2;
const scale = 40;

function px(x) {
    return center + x * scale;
}

function py(y) {
    return center - y * scale;
}

// рисует фигуры 
function drawGraph(r) {
    context.clearRect(0, 0, canvas.width, canvas.height);
    context.fillStyle = "rgba(191, 109, 232, 0.55)";

    // треугольник
    context.beginPath();
    context.moveTo(px(0), py(0));
    context.lineTo(px(r), py(0));
    context.lineTo(px(0), py(r / 2));
    context.closePath();
    context.fill();

    // прямоугольник 
    context.fillRect(
        px(0),
        py(0),
        r * scale,
        (r / 2) * scale
    );

    // четверть круга
    context.beginPath();
    context.moveTo(px(0), py(0));
    context.arc(
        px(0),
        py(0),
        (r / 2) * scale,
        Math.PI / 2,
        Math.PI,
        false
    );
    context.closePath();
    context.fill();

    drawAxes();
}

// координатные оси и подписи
function drawAxes() {
    context.strokeStyle = "#222";
    context.lineWidth = 1.5;

    context.beginPath();
    context.moveTo(10, center);
    context.lineTo(390, center);
    context.moveTo(center, 390);
    context.lineTo(center, 10);
    context.stroke();

    // стрелки осей
    context.beginPath();
    context.moveTo(390, center);
    context.lineTo(382, center - 4);
    context.moveTo(390, center);
    context.lineTo(382, center + 4);
    context.moveTo(center, 10);
    context.lineTo(center - 4, 18);
    context.moveTo(center, 10);
    context.lineTo(center + 4, 18);
    context.stroke();

    context.fillStyle = "#222";
    context.font = "12px Arial";
    context.fillText("X", canvas.width - 20, center - 8);
    context.fillText("Y", center + 8, 20);
    context.fillText("0", center + 6, center + 14);

    // подписи делений по осям
    context.textAlign = "center";
    context.textBaseline = "top";

    for (let value = -4; value <= 4; value++) {
        if (value === 0) continue;

        const xPosition = px(value);
        const yPosition = py(value);

        context.beginPath();
        context.moveTo(xPosition, center - 4);
        context.lineTo(xPosition, center + 4);
        context.moveTo(center - 4, yPosition);
        context.lineTo(center + 4, yPosition);
        context.stroke();

        context.fillText(String(value), xPosition, center + 7);

        context.textAlign = "left";
        context.textBaseline = "middle";
        context.fillText(String(value), center + 7, yPosition);

        context.textAlign = "center";
        context.textBaseline = "top";
    }

    context.textAlign = "start";
    context.textBaseline = "alphabetic";
}

function drawPoint(x, y, hit) {
    context.beginPath();
    context.arc(px(x), py(y), 4, 0, Math.PI * 2);
    context.fillStyle = hit ? "#157f37" : "#b00020";
    context.fill();
}

// добавляем результат в таблицу
function addResultToTable(result) {
    const row = document.createElement("tr");

    const values = [
        result.x,
        result.y,
        result.r,
        result.hit ? "Да" : "Нет",
        result.time
    ];

    values.forEach(value => {
        const cell = document.createElement("td");
        cell.textContent = String(value);
        row.appendChild(cell);
    });

    resultsBody.appendChild(row);
}

// перерисовываем таблицу и точки по истории от сервера
function renderHistory(results) {
    resultHistory = results;
    resultsBody.replaceChildren();
    results.forEach(addResultToTable);

    redrawGraph();
}

function redrawGraph() {
    const r = Number(rInput.value);
    drawGraph(r);

    resultHistory.filter(result => Number(result.r) === r).forEach(result => {
        drawPoint(Number(result.x), Number(result.y), result.hit);
    });
}

function setRequestPending(pending) {
    requestPending = pending;
    submitButton.disabled = pending;
    submitButton.textContent = pending ? "Ожидайте…" : "Проверить";
    form.setAttribute("aria-busy", String(pending));
}

async function requestData(body) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);

    try {
        const response = await fetch(form.action, {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
                "Accept": "application/json"
            },
            body: body.toString(),
            cache: "no-store",
            signal: controller.signal
        });

        let data;
        try {
            data = await response.json();
        } catch (error) {
            if (error.name === "AbortError") throw error;
            throw new Error(`Сервер вернул некорректный JSON (HTTP ${response.status}).`);
        }

        if (!response.ok || data.error) {
            throw new Error(data.error || `HTTP ${response.status}`);
        }
        if (!Array.isArray(data.results) || !Number.isFinite(data.executionTime)) {
            throw new Error("Неверный формат ответа сервера.");
        }

        renderHistory(data.results);
        executionInfo.textContent =
            `Время сервера: ${data.currentTime}; ` +
            `обработка запроса: ${data.executionTime.toFixed(3)} мс`;
    } catch (error) {
        if (error.name === "AbortError") {
            throw new Error("Сервер не ответил за 15 секунд. Проверьте его доступность.");
        }
        throw error;
    } finally {
        clearTimeout(timeout);
    }
}

// запрашиваем историю результатов у сервера
async function loadHistory() {
    setRequestPending(true);
    try {
        await requestData(new URLSearchParams({ action: "history" }));
    } finally {
        setRequestPending(false);
    }
}

// при изменении R обновляем рисунок
rInput.addEventListener("change", redrawGraph);

// обрабатываем отправку формы и посылаем данные методом POST
form.addEventListener("submit", async event => {
    event.preventDefault();
    if (requestPending) return;
    errorMessage.textContent = "";

    const xText = document.getElementById("x").value
        .trim()
        .replace(",", ".");

    if (xText === "") {
        errorMessage.textContent = "Введите значение X.";
        return;
    }

    const x = Number(xText);

    if (xText.length > 64 || !decimalPattern.test(xText)
            || !Number.isFinite(x) || x < -3 || x > 3) {
        errorMessage.textContent = "X должен быть числом от -3 до 3.";
        return;
    }

    const selectedY = [
        ...document.querySelectorAll('input[name="y"]:checked')
    ].map(input => Number(input.value));

    if (selectedY.length === 0 || selectedY.some(y => !Number.isInteger(y) || y < -4 || y > 4)) {
        errorMessage.textContent = "Выберите хотя бы одно значение Y.";
        return;
    }

    const r = Number(rInput.value);
    if (!allowedR.includes(r)) {
        errorMessage.textContent = "R должен быть одним из значений 1, 1.5, 2, 2.5, 3.";
        return;
    }
    const body = new URLSearchParams();

    body.set("x", xText);
    body.set("r", String(r));

    // y может повторяться, если несколько флажков
    selectedY.forEach(y => {
        body.append("y", String(y));
    });

    setRequestPending(true);
    try {
        await requestData(body);
    } catch (error) {
        errorMessage.textContent =
            `Не удалось выполнить запрос: ${error.message}`;
    } finally {
        setRequestPending(false);
    }
});

// при открытии страницы рисуем область и загружаем историю
drawGraph(Number(rInput.value));

loadHistory().catch(error => {
    executionInfo.textContent =
        `История пока недоступна: ${error.message}`;
});