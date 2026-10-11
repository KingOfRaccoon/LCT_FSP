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
  examples: TestCase[];
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
    examples: [
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
    examples: [
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
    examples: [
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
  },
];

export function CodeRunnerSandbox() {
  const [selectedTaskId, setSelectedTaskId] = useState(TASKS[0].id);
  const currentTask = TASKS.find((task) => task.id === selectedTaskId) || TASKS[0];
  const [codes, setCodes] = useState<Record<string, string>>(() => Object.fromEntries(TASKS.map((task) => [task.id, task.starterCode])));
  const [examplesOpen, setExamplesOpen] = useState(false);
  const currentCode = codes[currentTask.id] ?? currentTask.starterCode;

  const handleCodeChange = (newCode: string) => setCodes((current) => ({ ...current, [currentTask.id]: newCode }));
  const handleReset = () => handleCodeChange(currentTask.starterCode);

  return (
    <div className="sandbox-container">
      <div className="sandbox-task-selector">
        {TASKS.map((task) => (
          <button
            key={task.id}
            type="button"
            className={`sandbox-task-tab ${selectedTaskId === task.id ? 'active' : ''}`}
            onClick={() => setSelectedTaskId(task.id)}
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
        <p role="note" style={{ marginBottom: 0, color: 'var(--color-text-muted, #64748b)', fontSize: '0.85rem' }}>
          Учебный редактор хранит код как текст. Python здесь не запускается; примеры ниже описывают ожидаемое поведение и не проверяют решение.
        </p>
      </div>

      <div className="sandbox-workspace">
        <div className="sandbox-editor-panel">
          <div className="sandbox-editor-header">
            <div className="file-tab"><span>🐍</span><span>solution.py</span></div>
            <div style={{ fontSize: '0.75rem', color: '#9ca3af' }}>Python · текстовый пример</div>
          </div>
          <textarea
            className="sandbox-editor-textarea"
            value={currentCode}
            onChange={(event) => handleCodeChange(event.target.value)}
            spellCheck={false}
            autoCapitalize="off"
            autoComplete="off"
            rows={18}
            aria-label="Черновик кода для учебного примера"
          />
          <div className="sandbox-editor-actions">
            <button type="button" className="button button-secondary button-small" onClick={handleReset} style={{ color: '#d4d4d4', borderColor: '#444' }}>
              ↺ Сбросить к шаблону
            </button>
            <button type="button" className="button button-primary" onClick={() => setExamplesOpen((open) => !open)} style={{ minWidth: '160px' }}>
              {examplesOpen ? 'Скрыть примеры' : 'Показать примеры'}
            </button>
          </div>
        </div>

        <div className="sandbox-output-panel">
          <div className="sandbox-output-header"><h3>Ожидаемое поведение</h3></div>
          <div className="sandbox-output-content">
            {!examplesOpen ? (
              <div style={{ padding: '2rem 1rem', textAlign: 'center', color: '#64748b' }}>
                <strong>Код не выполняется и не оценивается</strong>
                <p style={{ margin: '0.25rem 0 0 0', fontSize: '0.85rem' }}>Покажите примеры, чтобы прочитать условия и ожидаемое поведение.</p>
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                {currentTask.examples.map((example) => (
                  <div key={example.id} className="sandbox-test-item">
                    <div className="sandbox-test-title"><span>{example.name}</span></div>
                    <div className="sandbox-test-detail">{example.description}<br /><strong>Ожидаемое поведение:</strong> {example.expected}</div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
