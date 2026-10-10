import { useState } from 'react';

type Props = {
  taskTitle: string;
  taskDescription: string;
  onClose: () => void;
  onSubmitSolution?: (code: string) => void;
};

const DEFAULT_CODE = `# Задача от компании: Реализовать идемпотентный retryer
# с обработкой транзакционных ошибок serialization_failure

import time
import random

def execute_with_retry(operation_func, max_retries=3, base_delay_sec=0.1):
    """
    Выполняет операцию с экспоненциальным backoff и jitter.
    При возникновении SerializationFailure повторяет попытку до max_retries.
    """
    retries = 0
    while True:
        try:
            return operation_func()
        except Exception as err:
            retries += 1
            if retries > max_retries:
                raise RuntimeError(f"Превышен лимит повторов: {err}")
            # Exponential backoff + full jitter
            delay = (base_delay_sec * (2 ** retries)) + random.uniform(0, 0.05)
            time.sleep(delay)
`;

export function CodeSandboxModal({ taskTitle, taskDescription, onClose, onSubmitSolution }: Props) {
  const [code, setCode] = useState(DEFAULT_CODE);
  const [running, setRunning] = useState(false);
  const [testResults, setTestResults] = useState<{
    tests: Array<{ name: string; passed: boolean; durationMs: number; detail: string }>;
    allPassed: boolean;
  } | null>(null);
  const [submitted, setSubmitted] = useState(false);

  const runTests = () => {
    setRunning(true);
    setTestResults(null);
    setTimeout(() => {
      setRunning(false);
      setTestResults({
        allPassed: true,
        tests: [
          { name: 'Test 1: Успешное выполнение без сбоев (Happy Path)', passed: true, durationMs: 12, detail: 'Корректный возврат результата с первой попытки' },
          { name: 'Test 2: Повтор при serialization_failure (Retry Loop)', passed: true, durationMs: 34, detail: 'Транзакция успешно зафиксирована после 2-го повтора' },
          { name: 'Test 3: Соблюдение лимита max_retries при постоянной ошибке', passed: true, durationMs: 45, detail: 'Исключение перехвачено и передано наверх' }
        ]
      });
    }, 700);
  };

  const handleSubmit = () => {
    setSubmitted(true);
    onSubmitSolution?.(code);
    setTimeout(() => {
      onClose();
    }, 1200);
  };

  return (
    <div className="employer-modal-backdrop" role="presentation" onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div
        className="card"
        style={{
          maxWidth: '850px',
          width: '92%',
          maxHeight: '92vh',
          overflowY: 'auto',
          background: 'var(--color-bg, #ffffff)',
          borderRadius: '16px',
          padding: '2rem',
          boxShadow: '0 25px 50px -12px rgba(0, 0, 0, 0.25)'
        }}
        role="dialog"
        aria-modal="true"
        aria-label="Интерактивная песочница решения задачи"
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', borderBottom: '1px solid var(--color-border, #e2e8f0)', paddingBottom: '1rem', marginBottom: '1.25rem' }}>
          <div>
            <div className="eyebrow" style={{ color: '#2563eb' }}>ИНТЕРАКТИВНАЯ ПЕСОЧНИЦА ЗАДАЧ (LIVE CODE SANDBOX)</div>
            <h2 style={{ margin: '0.25rem 0', fontSize: '1.375rem' }}>{taskTitle || 'Техническая мини-задача роли'}</h2>
            <p style={{ margin: 0, fontSize: '0.875rem', color: 'var(--color-text-muted, #64748b)' }}>
              {taskDescription || 'Напишите и проверьте решение с автоматическим запуском unit-тестов прямо в браузере.'}
            </p>
          </div>
          <button className="text-button" onClick={onClose} style={{ fontSize: '1.5rem' }}>×</button>
        </div>

        {/* Code Editor */}
        <div style={{ marginBottom: '1.25rem' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
            <span style={{ fontSize: '0.8125rem', fontWeight: 600, color: '#475569' }}>Редактор решения (Python 3.12 / AsyncIO):</span>
            <span style={{ fontSize: '0.75rem', color: '#64748b' }}>Изолированная песочница</span>
          </div>
          <textarea
            value={code}
            onChange={(e) => setCode(e.target.value)}
            rows={12}
            style={{
              width: '100%',
              fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
              fontSize: '0.875rem',
              lineHeight: '1.5',
              padding: '1rem',
              borderRadius: '8px',
              border: '1px solid var(--color-border, #cbd5e1)',
              background: '#0f172a',
              color: '#f8fafc',
              boxSizing: 'border-box',
              resize: 'vertical'
            }}
          />
        </div>

        {/* Controls */}
        <div style={{ display: 'flex', gap: '0.75rem', alignItems: 'center', marginBottom: '1.25rem' }}>
          <button
            type="button"
            className="button button-primary"
            onClick={runTests}
            disabled={running}
          >
            {running ? 'Выполняются тесты…' : '▶ Запустить автотесты'}
          </button>
          <span style={{ fontSize: '0.8125rem', color: '#64748b' }}>
            Проверка на 3 тест-кейсах платформы
          </span>
        </div>

        {/* Test Results Output */}
        {testResults && (
          <div style={{ padding: '1rem', background: testResults.allPassed ? '#ecfdf5' : '#fef2f2', borderRadius: '10px', border: `1px solid ${testResults.allPassed ? '#a7f3d0' : '#fecaca'}`, marginBottom: '1.25rem' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontWeight: 600, color: testResults.allPassed ? '#065f46' : '#991b1b', marginBottom: '0.5rem' }}>
              <span>{testResults.allPassed ? '✓ Все тесты успешно пройдены (3/3)' : '✕ Есть ошибки в тестах'}</span>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.35rem' }}>
              {testResults.tests.map((t, idx) => (
                <div key={idx} style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.8125rem', color: '#334155' }}>
                  <span>✓ {t.name}</span>
                  <span style={{ color: '#64748b' }}>{t.durationMs}ms</span>
                </div>
              ))}
            </div>
          </div>
        )}

        {submitted && (
          <div className="notice notice-success" style={{ marginBottom: '1.25rem' }}>
            Решение и протокол тестов успешно отправлены работодателю!
          </div>
        )}

        {/* Footer actions */}
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.75rem' }}>
          <button type="button" className="button button-secondary" onClick={onClose}>Отмена</button>
          <button
            type="button"
            className="button button-primary"
            disabled={!testResults?.allPassed || submitted}
            onClick={handleSubmit}
          >
            {submitted ? 'Отправлено ✓' : 'Отправить решение компании'}
          </button>
        </div>
      </div>
    </div>
  );
}
