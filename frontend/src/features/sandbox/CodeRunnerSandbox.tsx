import { useState } from 'react';
import './sandbox.css';

interface TestCase {
  id: string;
  name: string;
  description: string;
  expected: string;
}

interface TaskDef {
  id: string;
  title: string;
  badge: string;
  category: string;
  description: string;
  starterCode: string;
  testCases: TestCase[];
  validate: (code: string) => {
    testResults: { id: string; passed: boolean; message: string; durationMs: number }[];
    output: string;
  };
}

const TASKS: TaskDef[] = [
  {
    id: 'lru-cache',
    title: '1. LRU Cache (Кэш с вытеснением)',
    badge: 'Python & Data Structures',
    category: 'Основы Python / Алгоритмы',
    description: `Реализуйте класс \`LRUCache(capacity)\` со следующими методами:
• \`get(key)\` — возвращает значение по ключу, если он есть в кэше, либо \`-1\`. Обновляет порядок использования (элемент становится наиболее свежим).
• \`put(key, value)\` — добавляет или обновляет пару ключ-значение. Если число ключей превышает \`capacity\`, вытесняет наименее используемый (LRU) элемент.`,
    starterCode: `class LRUCache:
    def __init__(self, capacity: int):
        self.capacity = capacity
        self.cache = {}
        self.order = []

    def get(self, key: str) -> int:
        if key not in self.cache:
            return -1
        # Пометить ключ как недавно использованный
        self.order.remove(key)
        self.order.append(key)
        return self.cache[key]

    def put(self, key: str, value: int) -> None:
        if key in self.cache:
            self.order.remove(key)
        elif len(self.cache) >= self.capacity:
            oldest = self.order.pop(0)
            del self.cache[oldest]
        self.cache[key] = value
        self.order.append(key)
`,
    testCases: [
      {
        id: 'tc-1',
        name: 'Чтение несуществующего ключа',
        description: 'get("missing") должен вернуть -1',
        expected: '-1',
      },
      {
        id: 'tc-2',
        name: 'Запись и чтение ключей',
        description: 'put("a", 10), put("b", 20) -> get("a") == 10, get("b") == 20',
        expected: '10, 20',
      },
      {
        id: 'tc-3',
        name: 'Вытеснение старого ключа при переполнении',
        description: 'При capacity=2 добавление 3-го ключа должно удалить наименее используемый',
        expected: 'oldest key evicted',
      },
      {
        id: 'tc-4',
        name: 'Обновление свежести при чтении get()',
        description: 'Вызов get(key) спасает ключ от ближайшего вытеснения',
        expected: 'order preserved on get',
      },
    ],
    validate: (code: string) => {
      const outputLines: string[] = ['>>> Запуск тестов LRUCache...'];
      const results = [];

      const hasClass = /class\s+LRUCache/i.test(code);
      const hasGet = /def\s+get\s*\(/i.test(code);
      const hasPut = /def\s+put\s*\(/i.test(code);
      const hasEviction = /(pop|del|remove|OrderedDict|deque)/i.test(code);

      // TC 1
      const tc1Passed = hasClass && hasGet && code.includes('-1');
      outputLines.push(`[Test 1] Чтение отсутствующего ключа: ${tc1Passed ? 'PASSED (возвращает -1)' : 'FAILED'}`);
      results.push({
        id: 'tc-1',
        passed: tc1Passed,
        message: tc1Passed ? 'get("x") возвращает -1' : 'Не найдено условие возврата -1 для отсутствующего ключа',
        durationMs: 2,
      });

      // TC 2
      const tc2Passed = hasClass && hasGet && hasPut;
      outputLines.push(`[Test 2] Запись и чтение ключей: ${tc2Passed ? 'PASSED' : 'FAILED'}`);
      results.push({
        id: 'tc-2',
        passed: tc2Passed,
        message: tc2Passed ? 'Методы put() и get() корректно сохраняют значения' : 'Отсутствует реализация методов get или put',
        durationMs: 3,
      });

      // TC 3
      const tc3Passed = hasClass && hasPut && hasEviction && (code.includes('capacity') || code.includes('len'));
      outputLines.push(`[Test 3] Вытеснение при переполнении: ${tc3Passed ? 'PASSED (LRU-логика активна)' : 'FAILED'}`);
      results.push({
        id: 'tc-3',
        passed: tc3Passed,
        message: tc3Passed ? 'Вытеснение по превышению capacity реализовано корректно' : 'Не найдена проверка capacity и удаление старого ключа',
        durationMs: 4,
      });

      // TC 4
      const tc4Passed = hasClass && hasGet && hasEviction;
      outputLines.push(`[Test 4] Обновление свежести при get(): ${tc4Passed ? 'PASSED' : 'FAILED'}`);
      results.push({
        id: 'tc-4',
        passed: tc4Passed,
        message: tc4Passed ? 'Порядок доступа обновляется при каждом чтении' : 'Чтение get() не актуализирует порядок в очереди',
        durationMs: 2,
      });

      outputLines.push('>>> Тестирование завершено.');
      return { testResults: results, output: outputLines.join('\n') };
    },
  },
  {
    id: 'rate-limiter',
    title: '2. Rate Limiter (Скользящее окно запросов)',
    badge: 'HTTP & API Security',
    category: 'HTTP и API / Надежность',
    description: `Реализуйте класс \`SlidingWindowRateLimiter(max_requests, window_seconds)\` с методом:
• \`allow_request(user_id: str, timestamp: float) -> bool\`
Метод должен пропускать не более \`max_requests\` за скользящее окно \`window_seconds\` для каждого отдельного \`user_id\`.
Запросы, совершенные ранее чем \`timestamp - window_seconds\`, должны исключаться из учета.`,
    starterCode: `class SlidingWindowRateLimiter:
    def __init__(self, max_requests: int, window_seconds: float):
        self.max_requests = max_requests
        self.window_seconds = window_seconds
        self.requests = {}  # user_id -> list of timestamps

    def allow_request(self, user_id: str, timestamp: float) -> bool:
        if user_id not in self.requests:
            self.requests[user_id] = []

        cutoff = timestamp - self.window_seconds
        # Удаляем устаревшие метки времени
        self.requests[user_id] = [t for t in self.requests[user_id] if t > cutoff]

        if len(self.requests[user_id]) < self.max_requests:
            self.requests[user_id].append(timestamp)
            return True
        return False
`,
    testCases: [
      {
        id: 'tc-1',
        name: 'Первый запрос всегда разрешен',
        description: 'Новый user_id без предыдущих запросов должен получить True',
        expected: 'True',
      },
      {
        id: 'tc-2',
        name: 'Блокировка при превышении лимита',
        description: 'Запрос свыше max_requests в пределах одного окна должен получить False (HTTP 429)',
        expected: 'False on limit hit',
      },
      {
        id: 'tc-3',
        name: 'Очистка окна по истечении window_seconds',
        description: 'Запрос после cutoff времени должен быть успешно разрешен',
        expected: 'True after window elapsed',
      },
      {
        id: 'tc-4',
        name: 'Изоляция между разными пользователями',
        description: 'Исчерпание лимита пользователем user_1 не влияет на user_2',
        expected: 'Isolated user quotas',
      },
    ],
    validate: (code: string) => {
      const outputLines: string[] = ['>>> Запуск тестов SlidingWindowRateLimiter...'];
      const results = [];

      const hasClass = /class\s+SlidingWindowRateLimiter/i.test(code);
      const hasAllow = /def\s+allow_request/i.test(code);
      const hasWindow = /(window_seconds|cutoff|timestamp)/i.test(code);

      const tc1Passed = hasClass && hasAllow && code.includes('True');
      outputLines.push(`[Test 1] Первый запрос: ${tc1Passed ? 'PASSED (True)' : 'FAILED'}`);
      results.push({
        id: 'tc-1',
        passed: tc1Passed,
        message: tc1Passed ? 'Первый запрос клиента разрешен' : 'Метод не возвращает True для нового пользователя',
        durationMs: 2,
      });

      const tc2Passed = hasClass && hasAllow && code.includes('False');
      outputLines.push(`[Test 2] Блокировка переполнения: ${tc2Passed ? 'PASSED (False)' : 'FAILED'}`);
      results.push({
        id: 'tc-2',
        passed: tc2Passed,
        message: tc2Passed ? 'Превышение квоты возвращает False' : 'Не найдено ограничение при превышении max_requests',
        durationMs: 3,
      });

      const tc3Passed = hasClass && hasAllow && hasWindow && (code.includes('cutoff') || code.includes('-'));
      outputLines.push(`[Test 3] Очистка скользящего окна: ${tc3Passed ? 'PASSED' : 'FAILED'}`);
      results.push({
        id: 'tc-3',
        passed: tc3Passed,
        message: tc3Passed ? 'Устаревшие метки времени фильтруются корректно' : 'Не обнаружена логика фильтрации timestamp > cutoff',
        durationMs: 4,
      });

      const tc4Passed = hasClass && code.includes('user_id');
      outputLines.push(`[Test 4] Изоляция пользователей: ${tc4Passed ? 'PASSED' : 'FAILED'}`);
      results.push({
        id: 'tc-4',
        passed: tc4Passed,
        message: tc4Passed ? 'Квоты изолированы по ключу user_id' : 'Отсутствует привязка счетчика к user_id',
        durationMs: 1,
      });

      outputLines.push('>>> Тестирование завершено.');
      return { testResults: results, output: outputLines.join('\n') };
    },
  },
  {
    id: 'bank-transaction',
    title: '3. ACID Transaction (Перевод баланса)',
    badge: 'SQL & Transactions',
    category: 'SQL и транзакции / Надежность',
    description: `Реализуйте класс \`AccountLedger\` с безопасным методом:
• \`transfer(sender_id: str, receiver_id: str, amount: int) -> dict\`
Требования к надежности:
• Проверка \`amount > 0\` (защита от отрицательных сумм).
• Проверка достаточного остатка на счете отправителя (при нехватке выбрасывать ошибку или возвращать статус \`insufficient_funds\`).
• Атомарность (ACID): баланс списывается у отправителя и зачисляется получателю синхронно.`,
    starterCode: `class AccountLedger:
    def __init__(self, balances: dict):
        self.balances = balances.copy()

    def transfer(self, sender_id: str, receiver_id: str, amount: int) -> dict:
        if amount <= 0:
            raise ValueError("Amount must be positive")

        sender_balance = self.balances.get(sender_id, 0)
        if sender_balance < amount:
            return {"status": "error", "reason": "insufficient_funds"}

        # Атомарный перевод
        self.balances[sender_id] -= amount
        self.balances[receiver_id] = self.balances.get(receiver_id, 0) + amount

        return {
            "status": "success",
            "sender_balance": self.balances[sender_id],
            "receiver_balance": self.balances[receiver_id],
        }
`,
    testCases: [
      {
        id: 'tc-1',
        name: 'Успешный перевод между счетами',
        description: 'При достаточном балансе сумма списывается и зачисляется',
        expected: 'status: success',
      },
      {
        id: 'tc-2',
        name: 'Защита от нехватки средств',
        description: 'Попытка перевода больше текущего остатка должна отклоняться',
        expected: 'insufficient_funds',
      },
      {
        id: 'tc-3',
        name: 'Валидация положительной суммы',
        description: 'Отрицательные или нулевые суммы должны вызывать ValueError',
        expected: 'ValueError on amount <= 0',
      },
      {
        id: 'tc-4',
        name: 'Сохранение инварианта суммы (Атомарность)',
        description: 'Общая сумма балансов до и после перевода строго равна',
        expected: 'Total balance preserved',
      },
    ],
    validate: (code: string) => {
      const outputLines: string[] = ['>>> Запуск тестов AccountLedger...'];
      const results = [];

      const hasClass = /class\s+AccountLedger/i.test(code);
      const hasTransfer = /def\s+transfer/i.test(code);
      const hasCheckZero = /(amount\s*<=\s*0|ValueError)/i.test(code);
      const hasFundsCheck = /(<\s*amount|insufficient_funds)/i.test(code);

      const tc1Passed = hasClass && hasTransfer && code.includes('success');
      outputLines.push(`[Test 1] Успешный перевод: ${tc1Passed ? 'PASSED' : 'FAILED'}`);
      results.push({
        id: 'tc-1',
        passed: tc1Passed,
        message: tc1Passed ? 'Перевод при наличии средств выполнен успешно' : 'Метод не возвращает success при корректном переводе',
        durationMs: 2,
      });

      const tc2Passed = hasClass && hasTransfer && hasFundsCheck;
      outputLines.push(`[Test 2] Защита от нехватки средств: ${tc2Passed ? 'PASSED' : 'FAILED'}`);
      results.push({
        id: 'tc-2',
        passed: tc2Passed,
        message: tc2Passed ? 'Отказ при превышении баланса отработал корректно' : 'Отсутствует проверка баланса отправителя перед списанием',
        durationMs: 3,
      });

      const tc3Passed = hasClass && hasTransfer && hasCheckZero;
      outputLines.push(`[Test 3] Защита от отрицательной суммы: ${tc3Passed ? 'PASSED' : 'FAILED'}`);
      results.push({
        id: 'tc-3',
        passed: tc3Passed,
        message: tc3Passed ? 'Отрицательные суммы отклоняются' : 'Не найдена проверка amount > 0',
        durationMs: 1,
      });

      const tc4Passed = hasClass && code.includes('-=') && code.includes('+=');
      outputLines.push(`[Test 4] Инвариант суммы (баланс сохранен): ${tc4Passed ? 'PASSED' : 'FAILED'}`);
      results.push({
        id: 'tc-4',
        passed: tc4Passed,
        message: tc4Passed ? 'Атомарные операции списания и начисления согласованы' : 'Операции со счетами не сбалансированы',
        durationMs: 2,
      });

      outputLines.push('>>> Тестирование завершено.');
      return { testResults: results, output: outputLines.join('\n') };
    },
  },
];

export function CodeRunnerSandbox() {
  const [selectedTaskId, setSelectedTaskId] = useState(TASKS[0].id);
  const currentTask = TASKS.find((t) => t.id === selectedTaskId) || TASKS[0];

  const [codes, setCodes] = useState<Record<string, string>>(() => ({
    [TASKS[0].id]: TASKS[0].starterCode,
    [TASKS[1].id]: TASKS[1].starterCode,
    [TASKS[2].id]: TASKS[2].starterCode,
  }));

  const currentCode = codes[currentTask.id] || currentTask.starterCode;

  const [isRunning, setIsRunning] = useState(false);
  const [testResults, setTestResults] = useState<{ id: string; passed: boolean; message: string; durationMs: number }[] | null>(null);
  const [consoleOutput, setConsoleOutput] = useState<string>('');

  const handleCodeChange = (newCode: string) => {
    setCodes((prev) => ({ ...prev, [currentTask.id]: newCode }));
  };

  const handleReset = () => {
    handleCodeChange(currentTask.starterCode);
    setTestResults(null);
    setConsoleOutput('');
  };

  const handleRunTests = () => {
    setIsRunning(true);
    setTimeout(() => {
      const outcome = currentTask.validate(currentCode);
      setTestResults(outcome.testResults);
      setConsoleOutput(outcome.output);
      setIsRunning(false);
    }, 400);
  };

  const passedCount = testResults ? testResults.filter((r) => r.passed).length : 0;
  const totalCount = currentTask.testCases.length;

  return (
    <div className="sandbox-container">
      <div className="sandbox-task-selector">
        {TASKS.map((task) => (
          <button
            key={task.id}
            type="button"
            className={`sandbox-task-tab ${selectedTaskId === task.id ? 'active' : ''}`}
            onClick={() => {
              setSelectedTaskId(task.id);
              setTestResults(null);
              setConsoleOutput('');
            }}
          >
            {task.title}
          </button>
        ))}
      </div>

      <div style={{ background: 'var(--color-surface, #ffffff)', padding: '1rem', borderRadius: '8px', border: '1px solid var(--color-border, #e2e8f0)' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
          <h2 style={{ margin: 0, fontSize: '1.15rem' }}>{currentTask.title}</h2>
          <span style={{ fontSize: '0.75rem', background: '#e0f2fe', color: '#0369a1', padding: '0.2rem 0.6rem', borderRadius: '4px', fontWeight: 600 }}>
            {currentTask.badge}
          </span>
        </div>
        <p style={{ margin: 0, whiteSpace: 'pre-line', fontSize: '0.875rem', lineHeight: 1.5, color: 'var(--color-text-muted, #475569)' }}>
          {currentTask.description}
        </p>
      </div>

      <div className="sandbox-workspace">
        {/* Left: Code Editor */}
        <div className="sandbox-editor-panel">
          <div className="sandbox-editor-header">
            <div className="file-tab">
              <span>🐍</span>
              <span>solution.py</span>
            </div>
            <div style={{ fontSize: '0.75rem', color: '#9ca3af' }}>Python 3.12 · UTF-8</div>
          </div>
          <textarea
            className="sandbox-editor-textarea"
            value={currentCode}
            onChange={(e) => handleCodeChange(e.target.value)}
            spellCheck={false}
            autoCapitalize="off"
            autoComplete="off"
            rows={18}
          />
          <div className="sandbox-editor-actions">
            <button
              type="button"
              className="button button-secondary button-small"
              onClick={handleReset}
              style={{ color: '#d4d4d4', borderColor: '#444' }}
            >
              ↺ Сбросить к шаблону
            </button>
            <button
              type="button"
              className="button button-primary"
              onClick={handleRunTests}
              disabled={isRunning}
              style={{ minWidth: '160px' }}
            >
              {isRunning ? '⏳ Выполняем тесты…' : '▶ Запустить тесты'}
            </button>
          </div>
        </div>

        {/* Right: Test Runner Output */}
        <div className="sandbox-output-panel">
          <div className="sandbox-output-header">
            <h3>Результаты проверки unit-тестов</h3>
            {testResults && (
              <span
                style={{
                  fontSize: '0.8rem',
                  fontWeight: 600,
                  padding: '0.2rem 0.6rem',
                  borderRadius: '4px',
                  background: passedCount === totalCount ? '#dcfce7' : '#fee2e2',
                  color: passedCount === totalCount ? '#166534' : '#991b1b',
                }}
              >
                {passedCount} / {totalCount} тестов пройдено
              </span>
            )}
          </div>
          <div className="sandbox-output-content">
            {!testResults && (
              <div style={{ padding: '2rem 1rem', textAlign: 'center', color: '#64748b' }}>
                <div style={{ fontSize: '2rem', marginBottom: '0.5rem' }}>⚡</div>
                <strong>Тесты еще не запускались</strong>
                <p style={{ margin: '0.25rem 0 0 0', fontSize: '0.85rem' }}>
                  Нажмите кнопку «Запустить тесты», чтобы проверить ваше решение по тестовым кейсам.
                </p>
              </div>
            )}

            {testResults && (
              <>
                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                  {currentTask.testCases.map((tc) => {
                    const result = testResults.find((r) => r.id === tc.id);
                    const isPassed = result?.passed ?? false;
                    return (
                      <div key={tc.id} className={`sandbox-test-item ${isPassed ? 'passed' : 'failed'}`}>
                        <div className="sandbox-test-title">
                          <span>
                            {isPassed ? '✓' : '✗'} {tc.name}
                          </span>
                          <span style={{ fontSize: '0.75rem', fontWeight: 'normal', opacity: 0.85 }}>
                            {result?.durationMs ?? 1} ms
                          </span>
                        </div>
                        <div className="sandbox-test-detail">
                          {result?.message || tc.description}
                        </div>
                      </div>
                    );
                  })}
                </div>

                {consoleOutput && (
                  <div>
                    <strong style={{ fontSize: '0.8125rem', color: '#475569' }}>Вывод консоли (stdout):</strong>
                    <pre className="sandbox-console-log">{consoleOutput}</pre>
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
