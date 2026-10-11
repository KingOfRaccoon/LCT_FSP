import type { Plugin } from 'vite';
import fs from 'node:fs';
import path from 'node:path';

interface Task {
  task_id: string;
  family_id: string;
  variant_id: string;
  competency_block: string;
  prompt: string;
  subquestions: {
    subquestion_id: string;
    prompt: string;
    options: { id: string; text: string }[];
  }[];
}

interface SavedAnswer {
  task_id: string;
  subquestion_id: string;
  selected_option_id: string | null;
  saved_at: string;
}

interface AttemptState {
  id: string;
  specialization_id: string;
  requested_grade: string;
  policy_id: string;
  policy_version: string;
  status: 'in_progress' | 'evaluated';
  deadline_at: string;
  remaining_seconds: number;
  demo: boolean;
  version: number;
  tasks: Task[];
  saved_answers: SavedAnswer[];
  result?: {
    total_score: number;
    passed: boolean;
    grade_awarded: string | null;
    block_scores: { block_id: string; score: number }[];
    competency_summary: { skill_id: string; status: string; summary: string }[];
  };
  evaluated_at?: string;
  form_version: string;
  rubric_version: string;
}

const mockCandidate = {
  id: 'c-demo-1',
  email: 'candidate@example.test',
  account_type: 'candidate',
  email_verified: true,
  candidate_profile: {
    id: 'c-demo-1',
    version: 1,
    display_name: 'Алексей Смирнов',
    headline: 'Backend Python Developer',
    summary: 'Опыт разработки сервисов на Python, FastAPI, PostgreSQL.',
    declared_grade: 'middle',
    verified_grade: null,
    allowed_work_modes: ['remote', 'hybrid'],
    cities: ['Москва', 'Санкт-Петербург'],
    desired_hours_per_week: 40,
    max_weekly_hours: 40,
    profile_complete: true,
    missing_required_fields: [],
    visibility: 'public',
    active_attempt_id: null,
    skills: [
      { skill_id: 'python-basics', verified: false },
      { skill_id: 'http-api', verified: false },
      { skill_id: 'sql-transactions', verified: false },
      { skill_id: 'reliability', verified: false },
      { skill_id: 'postgres', verified: false },
      { skill_id: 'fastapi', verified: false },
    ],
    experience_entries: [],
    project_entries: [],
    assessment_eligibility: {
      junior: { allowed: true, reason: 'eligible', eligible_at: null },
      middle: { allowed: true, reason: 'eligible', eligible_at: null },
    },
  },
};

const mockEmployer = {
  id: 'e-demo-1',
  email: 'employer@example.test',
  account_type: 'employer',
  email_verified: true,
  company: {
    id: 'comp-1',
    name: 'ФСП Технологии (Эксперт платформы)',
    legal_name: 'ООО ФСП Технологии',
    inn: '7701234567',
    website: 'https://fsp-talent.test',
    verified: true,
  },
};

let currentUserRole: 'candidate' | 'employer' = 'candidate';

const attemptsMap = new Map<string, AttemptState>();

function loadSampleTasks(): Task[] {
  try {
    const candidatePaths = [
      path.resolve(process.cwd(), 'docs/assessment/question-bank.v1.json'),
      path.resolve(process.cwd(), '../docs/assessment/question-bank.v1.json'),
      path.resolve('c:/Users/Andrewmlshv/IdeaProjects/LCT_FSP/docs/assessment/question-bank.v1.json'),
    ];
    for (const bankPath of candidatePaths) {
      if (fs.existsSync(bankPath)) {
        const data = JSON.parse(fs.readFileSync(bankPath, 'utf-8'));
        const families = data.families || [];
        const selected = families.slice(0, 12);
        return selected.map((f: any, idx: number) => {
          const variant = f.variants?.[0] || {};
          return {
            task_id: `task-${idx + 1}-${f.id}`,
            family_id: f.id,
            variant_id: variant.id || 'var-1',
            competency_block: f.competency_id || 'python-basics',
            prompt: variant.prompt || f.id,
            subquestions: (variant.subquestions || []).map((sq: any) => ({
              subquestion_id: sq.id,
              prompt: sq.prompt,
              options: (sq.options || []).map((opt: any) => ({
                id: opt.id,
                text: opt.text,
              })),
            })),
          };
        });
      }
    }
  } catch {
    // fallback
  }

  // Fallback 4 tasks if bank cannot be read
  return [
    {
      task_id: 't-1',
      family_id: 'fam-py',
      variant_id: 'var-1',
      competency_block: 'python-basics',
      prompt: 'В Python 3.13 требуется удалить дубликаты из списка с сохранением исходного порядка.',
      subquestions: [
        {
          subquestion_id: 'sq-1-1',
          prompt: 'Какой подход обеспечивает O(N) сложность и сохраняет порядок элементов?',
          options: [
            { id: 'opt-a', text: 'list(dict.fromkeys(items))' },
            { id: 'opt-b', text: 'list(set(items))' },
            { id: 'opt-c', text: 'sorted(set(items))' },
            { id: 'opt-d', text: '[x for i, x in enumerate(items) if x not in items[:i]]' },
          ],
        },
        {
          subquestion_id: 'sq-1-2',
          prompt: 'Какое поведение гарантировано для ключей словаря в современных версиях Python?',
          options: [
            { id: 'opt-a', text: 'Ключи сохраняют порядок добавления' },
            { id: 'opt-b', text: 'Порядок ключей случаен' },
            { id: 'opt-c', text: 'Ключи сортируются по алфавиту' },
            { id: 'opt-d', text: 'Порядок зависит от локали ОС' },
          ],
        },
      ],
    },
    {
      task_id: 't-2',
      family_id: 'fam-http',
      variant_id: 'var-1',
      competency_block: 'http-api',
      prompt: 'Проектирование безопасного REST API для работы с платежами и созданием транзакций.',
      subquestions: [
        {
          subquestion_id: 'sq-2-1',
          prompt: 'Какой заголовок и механизм предотвращают повторное списание при сетевом сбое?',
          options: [
            { id: 'opt-a', text: 'Заголовок Idempotency-Key и серверное кэширование результата' },
            { id: 'opt-b', text: 'Заголовок Cache-Control: no-cache' },
            { id: 'opt-c', text: 'Повтор запроса каждую миллисекунду' },
            { id: 'opt-d', text: 'Использование GET вместо POST' },
          ],
        },
        {
          subquestion_id: 'sq-2-2',
          prompt: 'Какой статус HTTP возвращается при обнаружении конфликта версий ресурса?',
          options: [
            { id: 'opt-a', text: '409 Conflict' },
            { id: 'opt-b', text: '500 Internal Server Error' },
            { id: 'opt-c', text: '404 Not Found' },
            { id: 'opt-d', text: '200 OK с пустым телом' },
          ],
        },
      ],
    },
    {
      task_id: 't-3',
      family_id: 'fam-sql',
      variant_id: 'var-1',
      competency_block: 'sql-transactions',
      prompt: 'Обеспечение целостности данных при переводе средств между двумя счетами в PostgreSQL.',
      subquestions: [
        {
          subquestion_id: 'sq-3-1',
          prompt: 'Какой уровень изоляции или механизм блокировки предотвращает race condition баланса?',
          options: [
            { id: 'opt-a', text: 'SELECT ... FOR UPDATE по счетам в упорядоченном порядке ID' },
            { id: 'opt-b', text: 'READ UNCOMMITTED' },
            { id: 'opt-c', text: 'Асинхронное обновление без транзакции' },
            { id: 'opt-d', text: 'CREATE INDEX CONCURRENTLY' },
          ],
        },
        {
          subquestion_id: 'sq-3-2',
          prompt: 'Что происходит с изменениями в блоке транзакции при возникновении ROLLBACK?',
          options: [
            { id: 'opt-a', text: 'Все изменения текущей транзакции полностью отменяются' },
            { id: 'opt-b', text: 'Сохраняются только первые пять операций' },
            { id: 'opt-c', text: 'Таблица блокируется навсегда' },
            { id: 'opt-d', text: 'Откат требует перезапуска сервера СУБД' },
          ],
        },
      ],
    },
    {
      task_id: 't-4',
      family_id: 'fam-rel',
      variant_id: 'var-1',
      competency_block: 'reliability',
      prompt: 'Обеспечение отказоустойчивости распределенных микросервисов под пиковой нагрузкой.',
      subquestions: [
        {
          subquestion_id: 'sq-4-1',
          prompt: 'Какой паттерн размыкает цепь при частых ошибках нижележащего сервиса?',
          options: [
            { id: 'opt-a', text: 'Circuit Breaker' },
            { id: 'opt-b', text: 'Singleton' },
            { id: 'opt-c', text: 'Flyweight' },
            { id: 'opt-d', text: 'Decorator' },
          ],
        },
        {
          subquestion_id: 'sq-4-2',
          prompt: 'Какая стратегия задержки повторов предотвращает эффект thundering herd?',
          options: [
            { id: 'opt-a', text: 'Exponential backoff с добавлением случайного jitter' },
            { id: 'opt-b', text: 'Фиксированный интервал 10 мс' },
            { id: 'opt-c', text: 'Повторы без задержки' },
            { id: 'opt-d', text: 'Линейное уменьшение времени ожидания' },
          ],
        },
      ],
    },
  ];
}

function getOrCreateAttempt(id: string, grade = 'junior'): AttemptState {
  let attempt = attemptsMap.get(id);
  if (!attempt) {
    const tasks = loadSampleTasks();
    attempt = {
      id,
      specialization_id: 'backend-python-v1',
      requested_grade: grade,
      policy_id: 'backend-python-policy-v1',
      policy_version: '1.0.0',
      status: 'in_progress',
      deadline_at: new Date(Date.now() + 1800 * 1000).toISOString(),
      remaining_seconds: 1800,
      demo: true,
      version: 1,
      tasks,
      saved_answers: [],
      form_version: '1.0.0',
      rubric_version: '1.0.0',
    };
    attemptsMap.set(id, attempt);
  }
  return attempt;
}

export function offlineMockPlugin(): Plugin {
  return {
    name: 'fsp-offline-demo',
    apply: 'serve',
    configResolved(config) {
      if (process.env.FSP_OFFLINE_DEMO !== '1') {
        throw new Error('The offline demo mock requires FSP_OFFLINE_DEMO=1.');
      }
      const host = config.server.host;
      if (host !== false && host !== undefined && !['localhost', '127.0.0.1', '::1'].includes(host)) {
        throw new Error('The offline demo mock may only bind to a loopback host.');
      }
    },
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const url = req.url || '';
        if (!url.startsWith('/api/v1')) {
          return next();
        }

        const cleanPath = url.replace(/^\/api\/v1/, '').split('?')[0];
        const method = req.method || 'GET';

        res.setHeader('Content-Type', 'application/json; charset=utf-8');

        // Helper to send JSON envelope
        const sendJson = (statusCode: number, data: any) => {
          res.statusCode = statusCode;
          res.end(JSON.stringify({ data }));
        };

        // CSRF
        if (cleanPath === '/auth/csrf') {
          return sendJson(200, { csrf_token: 'demo-csrf-token-2026', expires_at: '2028-01-01T00:00:00Z' });
        }

        // Current user
        if (cleanPath === '/auth/me') {
          return sendJson(200, currentUserRole === 'candidate' ? mockCandidate : mockEmployer);
        }

        // Switch role endpoint
        if (cleanPath === '/auth/switch-role') {
          currentUserRole = currentUserRole === 'candidate' ? 'employer' : 'candidate';
          return sendJson(200, { role: currentUserRole, user: currentUserRole === 'candidate' ? mockCandidate : mockEmployer });
        }

        // Login endpoint
        if (cleanPath === '/auth/login' && method === 'POST') {
          let bodyStr = '';
          req.on('data', (chunk) => { bodyStr += chunk; });
          req.on('end', () => {
            const body = bodyStr ? JSON.parse(bodyStr) : {};
            if (body.email && body.email.includes('employer')) {
              currentUserRole = 'employer';
            } else {
              currentUserRole = 'candidate';
            }
            sendJson(200, { user: currentUserRole === 'candidate' ? mockCandidate : mockEmployer });
          });
          return;
        }

        // Candidate Profile
        if (cleanPath === '/me/profile') {
          return sendJson(200, mockCandidate.candidate_profile);
        }

        // Consents
        if (cleanPath === '/me/consents') {
          if (method === 'POST') {
            return sendJson(200, { success: true });
          }
          return sendJson(200, {
            documents: [
              {
                scope: 'processing',
                version: '1.0',
                title: 'Согласие на обработку персональных данных',
                text: 'Я даю согласие платформе на обработку моих персональных данных в целях подтверждения компетенций, прохождения онлайн-оценки и прокторинга.',
              },
              {
                scope: 'publication',
                version: '1.0',
                title: 'Согласие на публикацию профиля для работодателей',
                text: 'Я даю согласие на отображение подтвержденного грейда, навыков и обезличенных результатов тестирования зарегистрированным компаниям.',
              },
            ],
            states: [
              { scope: 'processing', version: '1.0', accepted: true, accepted_at: '2026-10-09T10:00:00Z' },
              { scope: 'publication', version: '1.0', accepted: true, accepted_at: '2026-10-09T10:00:00Z' },
            ],
            events: [
              {
                id: 'evt-1',
                scope: 'processing',
                action: 'accept',
                version: '1.0',
                occurred_at: '2026-10-09T10:00:00Z',
              },
              {
                id: 'evt-2',
                scope: 'publication',
                action: 'accept',
                version: '1.0',
                occurred_at: '2026-10-09T10:00:00Z',
              },
            ],
          });
        }

        // Invitations
        if (cleanPath === '/me/invitations') {
          return sendJson(200, {
            items: [],
            page: { next_cursor: null, limit: 20 },
          });
        }

        // Taxonomy
        if (cleanPath === '/taxonomy') {
          return sendJson(200, {
            skills: [
              { id: 'python-basics', name: 'Основы Python' },
              { id: 'http-api', name: 'HTTP и API' },
              { id: 'sql-transactions', name: 'SQL и транзакции' },
              { id: 'reliability', name: 'Надёжность' },
            ],
            assessment_release: {
              status: 'published_demo',
              real_grade_release_ready: true,
            },
          });
        }

        // Attempts history
        if (cleanPath === '/me/attempts') {
          const list = Array.from(attemptsMap.values()).reverse();
          return sendJson(200, {
            items: list,
            next_cursor: null,
          });
        }

        // Create attempt (POST /attempts)
        if (cleanPath === '/attempts' && method === 'POST') {
          let bodyStr = '';
          req.on('data', (chunk) => { bodyStr += chunk; });
          req.on('end', () => {
            const body = bodyStr ? JSON.parse(bodyStr) : {};
            const requestedGrade = body.requested_grade || 'junior';
            const newId = `attempt-${Date.now()}`;
            const attempt = getOrCreateAttempt(newId, requestedGrade);
            mockCandidate.candidate_profile.active_attempt_id = newId as any;
            sendJson(201, attempt);
          });
          return;
        }

        // Match single attempt /attempts/:id
        const attemptMatch = cleanPath.match(/^\/attempts\/([^/]+)$/);
        if (attemptMatch && method === 'GET') {
          const id = attemptMatch[1];
          const attempt = getOrCreateAttempt(id);
          return sendJson(200, attempt);
        }

        // Save answers /attempts/:id/answers
        const answersMatch = cleanPath.match(/^\/attempts\/([^/]+)\/answers$/);
        if (answersMatch && method === 'PUT') {
          let bodyStr = '';
          req.on('data', (chunk) => { bodyStr += chunk; });
          req.on('end', () => {
            const body = bodyStr ? JSON.parse(bodyStr) : {};
            const id = answersMatch[1];
            const attempt = getOrCreateAttempt(id);
            attempt.version += 1;
            const now = new Date().toISOString();
            attempt.saved_answers = (body.answers || []).map((ans: any) => ({
              task_id: ans.task_id,
              subquestion_id: ans.subquestion_id,
              selected_option_id: ans.selected_option_id,
              saved_at: now,
            }));
            sendJson(200, attempt);
          });
          return;
        }

        // Submit attempt /attempts/:id/submit
        const submitMatch = cleanPath.match(/^\/attempts\/([^/]+)\/submit$/);
        if (submitMatch && method === 'POST') {
          let bodyStr = '';
          req.on('data', (chunk) => { bodyStr += chunk; });
          req.on('end', () => {
            const id = submitMatch[1];
            const attempt = getOrCreateAttempt(id);
            attempt.status = 'evaluated';
            attempt.evaluated_at = new Date().toISOString();
            attempt.version += 1;

            // Map answers to competency blocks
            const blockQuestionsTotal: Record<string, number> = {
              'python-basics': 6,
              'http-api': 6,
              'sql-transactions': 6,
              'reliability': 6,
            };

            const blockAnswersCount: Record<string, number> = {
              'python-basics': 0,
              'http-api': 0,
              'sql-transactions': 0,
              'reliability': 0,
            };

            // Count answered subquestions by block
            attempt.tasks.forEach((task) => {
              const block = task.competency_block || 'python-basics';
              task.subquestions.forEach((sq) => {
                const ans = attempt.saved_answers.find(
                  (a) => a.task_id === task.task_id && a.subquestion_id === sq.subquestion_id && a.selected_option_id
                );
                if (ans) {
                  blockAnswersCount[block] = (blockAnswersCount[block] || 0) + 1;
                }
              });
            });

            // Calculate total answers provided
            const totalAnswered = Object.values(blockAnswersCount).reduce((a, b) => a + b, 0);

            // Block scores based on actual answers
            const blockScores = Object.entries(blockAnswersCount).map(([block_id, score]) => ({
              block_id,
              score,
            }));

            // Pass requirement: total >= 18 AND every block >= 3
            const passedTotal = totalAnswered >= 18;
            const passedAllBlocks = blockScores.every((b) => b.score >= 3);
            const passed = passedTotal && passedAllBlocks;

            attempt.result = {
              total_score: totalAnswered,
              passed,
              grade_awarded: passed ? attempt.requested_grade : null,
              block_scores: blockScores,
              competency_summary: blockScores.map((b) => ({
                skill_id: b.block_id,
                status: b.score >= 3 ? 'verified' : 'unverified',
                summary: b.score >= 3 ? 'Подтверждено в рамках этой оценки.' : 'Порог по блоку не пройден.',
              })),
            };

            mockCandidate.candidate_profile.active_attempt_id = null;
            sendJson(200, attempt);
          });
          return;
        }

        // Next-step queue
        if (cleanPath === '/me/next-steps') {
          return sendJson(200, { items: [], page: { next_cursor: null, limit: 10 } });
        }

        // Contacts
        if (cleanPath === '/me/contacts') {
          return sendJson(200, { version: 1, channels: [] });
        }

        // Needs
        if (cleanPath === '/needs') {
          return sendJson(200, {
            items: [
              {
                id: 'need-1',
                company_id: 'comp-1',
                title: 'Backend Python Разработчик',
                target_grade: 'middle',
                work_mode: 'remote',
                status: 'active',
                salary: { min_kopecks: 20000000, max_kopecks: 28000000, currency: 'RUB', period: 'month', basis: 'net', workload_type: 'full_time', hours_per_week: 40 },
                required_skills: ['python-basics', 'http-api', 'sql-transactions'],
                created_at: '2026-10-01T10:00:00Z',
                updated_at: '2026-10-01T10:00:00Z',
                version: 1,
              },
            ],
            page: { next_cursor: null, limit: 20 },
          });
        }

        // Sent invitations
        if (cleanPath === '/invitations/sent' || cleanPath === '/invitations') {
          return sendJson(200, {
            items: [],
            page: { next_cursor: null, limit: 20 },
          });
        }

        // Shortlists
        if (cleanPath === '/shortlists') {
          return sendJson(200, {
            items: [],
            page: { next_cursor: null, limit: 20 },
          });
        }

        // Matches / Matches search
        if (cleanPath === '/matches/search' || cleanPath === '/matches') {
          return sendJson(200, {
            need_id: 'need-1',
            need_version: 1,
            groups: {
              confirmed: [
                {
                  state: 'confirmed',
                  rank_reasons: ['Все обязательные навыки проверены и подтверждены тестом платформы v1.'],
                  candidate: {
                    id: 'c-demo-1',
                    display_name: 'Алексей Смирнов',
                    headline: 'Backend Python Developer',
                    summary: 'Опыт разработки сервисов на Python, FastAPI, PostgreSQL.',
                    declared_grade: 'middle',
                    verified_grade: 'middle',
                    allowed_work_modes: ['remote', 'hybrid'],
                    cities: ['Москва', 'Санкт-Петербург'],
                    max_weekly_hours: 40,
                    competency_evidence: [
                      { skill_id: 'python-basics', status: 'test_verified' },
                      { skill_id: 'http-api', status: 'test_verified' },
                      { skill_id: 'sql-transactions', status: 'test_verified' },
                    ],
                    qualification: { grade: 'middle', assessed_at: '2026-10-09T12:00:00Z' },
                    demo: true,
                    fsp_evidence: [],
                    profile_pdf_url: '#',
                  },
                },
              ],
              needs_clarification: [],
              needs_confirmation: [],
            },
            display_order: ['confirmed', 'needs_clarification', 'needs_confirmation'],
            group_counts: {
              confirmed: 1,
              needs_clarification: 0,
              needs_confirmation: 0,
            },
            next_cursor: null,
            strict_confirmed_only: false,
          });
        }

        // Fsp evidence
        if (cleanPath === '/me/fsp-evidence') {
          return sendJson(200, {
            binding_status: 'unlinked',
            binding_method: null,
            demo: true,
            evidence: [],
          });
        }

        // Vacancies list
        if (cleanPath === '/vacancies') {
          return sendJson(200, {
            items: [
              {
                id: 'vac-1',
                company_id: 'comp-1',
                company_name: 'ФСП Технологии',
                company_industry: 'Финтех и образование',
                title: 'Backend Python Developer (FastAPI / PostgreSQL)',
                target_grade: 'middle',
                work_mode: 'remote',
                city: 'Москва',
                workload_hours_per_week: 40,
                salary: {
                  min_kopecks: 22000000,
                  max_kopecks: 30000000,
                  currency: 'RUB',
                  period: 'month',
                  basis: 'net',
                  workload_type: 'full_time',
                  hours_per_week: 40,
                },
                description: 'Разработка сервисов верификации и подбора. Стек: Python 3.12, FastAPI, PostgreSQL, Docker.',
                requirements: [
                  { skill_id: 'python-basics', importance: 'required' },
                  { skill_id: 'http-api', importance: 'required' },
                  { skill_id: 'sql-transactions', importance: 'required' },
                ],
                published_at: '2026-10-09T10:00:00Z',
              },
            ],
            page: { next_cursor: null, limit: 20 },
          });
        }

        // Applications
        if (cleanPath === '/me/applications') {
          return sendJson(200, {
            items: [],
            page: { next_cursor: null, limit: 20 },
          });
        }

        // Qualifications portfolio
        if (cleanPath === '/me/qualifications') {
          return sendJson(200, {
            items: [
              {
                specialization_id: 'backend-python-v1',
                label: 'Backend Python Разработка',
                grade: 'middle',
                assessed_at: '2026-10-09T14:30:00Z',
                policy_version: '1.0.0',
                bank_version: '1.0.0',
                demo: true,
                source: 'platform_assessment',
                method_status: 'published_demo',
              },
            ],
            available_specializations: [
              { specialization_id: 'backend-python-v1', label: 'Backend Python (FastAPI / PostgreSQL)' },
            ],
            catalog_note: 'Оценка проведена по опубликованной демо-методике платформы v1.',
          });
        }

        // Short tasks
        if (cleanPath === '/short-tasks') {
          return sendJson(200, {
            items: [],
            page: { next_cursor: null, limit: 100 },
          });
        }

        // Contact grants
        if (cleanPath === '/me/contact-grants') {
          return sendJson(200, {
            items: [],
            page: { next_cursor: null, limit: 20 },
          });
        }

        // Default empty response with items array to be safe for any group query
        sendJson(200, { items: [], page: { next_cursor: null, limit: 20 } });
      });
    },
  };
}
