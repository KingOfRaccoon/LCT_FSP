import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { api, errorMessage, type Candidate, type Schemas } from '../../api';

const EMPTY_RECORDS: Schemas['RecruitingRecord'][] = [];

function candidateFromRecord(record: Schemas['RecruitingRecord']): CandidateOption {
  if (record.candidate_visibility === 'hidden') {
    return {
      id: record.candidate_id,
      displayName: 'Профиль скрыт',
      headline: null,
      verifiedGrade: null,
      declaredGrade: null,
      visibility: 'hidden',
      demo: false,
    };
  }
  return {
    id: record.candidate_id,
    displayName: record.candidate_display_name || 'Профиль скрыт',
    headline: null,
    visibility: record.candidate_visibility,
    demo: false,
  };
}

export interface CandidateOption {
  id: string;
  displayName: string;
  headline?: string | null;
  verifiedGrade?: string | null;
  declaredGrade?: string | null;
  visibility?: 'public' | 'hidden';
  demo?: boolean;
}

export interface CandidateAutocompleteProps {
  value: string;
  onChange: (candidate: CandidateOption | null) => void;
  existingRecords?: Schemas['RecruitingRecord'][];
  selectedCandidate?: CandidateOption | null;
  placeholder?: string;
  disabled?: boolean;
  readOnly?: boolean;
  required?: boolean;
  allowClear?: boolean;
  filterMode?: boolean;
  id?: string;
  className?: string;
}

export function CandidateAvatar({ name, size = 32 }: { name: string; size?: number }) {
  const initial = (name.trim()[0] || '?').toUpperCase();
  // Generate consistent pleasant background color based on name
  let hash = 0;
  for (let i = 0; i < name.length; i++) {
    hash = name.charCodeAt(i) + ((hash << 5) - hash);
  }
  const hues = [210, 220, 240, 260, 280, 160, 180];
  const hue = hues[Math.abs(hash) % hues.length];

  return (
    <div
      className="candidate-avatar"
      style={{
        width: `${size}px`,
        height: `${size}px`,
        borderRadius: '50%',
        backgroundColor: `hsl(${hue}, 65%, 45%)`,
        color: '#ffffff',
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        fontWeight: 600,
        fontSize: `${Math.round(size * 0.44)}px`,
        flexShrink: 0,
        userSelect: 'none',
      }}
      aria-hidden="true"
    >
      {initial}
    </div>
  );
}

export function CandidateAutocomplete({
  value,
  onChange,
  existingRecords = EMPTY_RECORDS,
  selectedCandidate,
  placeholder = 'Поиск кандидата по имени или направлению…',
  disabled = false,
  readOnly = false,
  required = false,
  allowClear = true,
  filterMode = false,
  id,
  className = '',
}: CandidateAutocompleteProps) {
  const generatedId = useId();
  const inputId = id || generatedId;
  const listboxId = `${inputId}-listbox`;

  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [highlightedIndex, setHighlightedIndex] = useState(-1);
  const [candidates, setCandidates] = useState<CandidateOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [retryCount, setRetryCount] = useState(0);

  const containerRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const existingRecordsRef = useRef(existingRecords);
  existingRecordsRef.current = existingRecords;

  useEffect(() => {
    setCandidates((current) => {
      const map = new Map(current.map((candidate) => [candidate.id, candidate]));
      existingRecords
        .filter((record) => record.candidate_id)
        .forEach((record) => {
          const current = map.get(record.candidate_id);
          if (record.candidate_visibility === 'hidden' || !current || current.visibility === 'hidden') {
            map.set(record.candidate_id, candidateFromRecord(record));
          }
        });
      return Array.from(map.values());
    });
  }, [existingRecords]);

  // Load public candidates from candidate bank
  useEffect(() => {
    let active = true;
    setLoading(true);
    setError('');

    const basePayload = {
      specialization_id: null,
      grade: null,
      category_status: 'any',
      skill_ids: [],
      fsp_status: 'any',
      limit: 50,
    };

    const recordCandidates: CandidateOption[] = existingRecordsRef.current
      .filter((rec) => rec.candidate_id)
      .map(candidateFromRecord);
    const optionsById = new Map<string, CandidateOption>();
    recordCandidates.forEach((candidate) => optionsById.set(candidate.id, candidate));
    const fetchedIds = new Set<string>();
    setCandidates(Array.from(optionsById.values()));

    const loadCandidates = async () => {
      const requestedCursors = new Set<string | null>();
      let cursor: string | null = null;
      let failure = '';
      try {
        while (active && !requestedCursors.has(cursor)) {
          requestedCursors.add(cursor);
          const response: { items: Candidate[]; next_cursor: string | null } = await api.post<{
            items: Candidate[];
            next_cursor: string | null;
          }>(
            '/candidates/search',
            { ...basePayload, cursor },
          );
          if (!active) return;

          (response.items || []).forEach((candidate) => {
            const ownRecord = existingRecordsRef.current.find((record) => record.candidate_id === candidate.id);
            if (ownRecord?.candidate_visibility === 'hidden') {
              optionsById.set(candidate.id, candidateFromRecord(ownRecord));
              fetchedIds.add(candidate.id);
              return;
            }
            if (fetchedIds.has(candidate.id)) return;
            fetchedIds.add(candidate.id);
            optionsById.set(candidate.id, {
              id: candidate.id,
              displayName: candidate.display_name,
              headline: candidate.headline,
              verifiedGrade: candidate.verified_grade,
              declaredGrade: candidate.declared_grade,
              visibility: 'public',
              demo: Boolean(candidate.demo),
            });
          });
          setCandidates(Array.from(optionsById.values()));

          const nextCursor: string | null = response.next_cursor ?? null;
          if (nextCursor === null) break;
          if (!nextCursor) {
            failure = 'Список кандидатов загружен частично: сервер вернул пустой курсор.';
            break;
          }
          if (requestedCursors.has(nextCursor)) {
            failure = 'Список кандидатов загружен частично: сервер повторил страницу.';
            break;
          }
          cursor = nextCursor;
        }
      } catch (err) {
        if (!active) return;
        failure = fetchedIds.size > 0
          ? `Список кандидатов загружен частично. ${errorMessage(err)}`
          : recordCandidates.length > 0
            ? `Не удалось загрузить полный список кандидатов. Показаны только кандидаты из уже сохранённых записей. ${errorMessage(err)}`
            : errorMessage(err);
      }

      if (active) setError(failure);
    };

    void loadCandidates().finally(() => {
      if (active) setLoading(false);
    });

    return () => {
      active = false;
    };
  }, [retryCount]);

  const retryCandidateSearch = () => {
    setRetryCount((count) => count + 1);
  };

  const candidateLoadError = error;

  // Derive currently chosen candidate
  const selectedId = value || selectedCandidate?.id;
  const hiddenSelectedRecord = existingRecords.find((record) =>
    record.candidate_id === selectedId && record.candidate_visibility === 'hidden');
  const currentSelected: CandidateOption | null =
    (hiddenSelectedRecord ? candidateFromRecord(hiddenSelectedRecord) : null) ||
    selectedCandidate ||
    candidates.find((c) => c.id === value) ||
    (value
      ? {
          id: value,
          displayName: `Кандидат (${value.slice(0, 8)}…)`,
          visibility: 'public',
        }
      : null);

  // Close dropdown when clicking outside
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  // Filter candidates based on search query
  const candidatesForDisplay = new Map(candidates.map((candidate) => [candidate.id, candidate]));
  existingRecords
    .filter((record) => record.candidate_visibility === 'hidden')
    .forEach((record) => candidatesForDisplay.set(record.candidate_id, candidateFromRecord(record)));
  const filteredCandidates = Array.from(candidatesForDisplay.values()).filter((item) => {
    if (!query.trim()) return true;
    const lower = query.toLowerCase();
    const matchesName = item.displayName.toLowerCase().includes(lower);
    const matchesHeadline = item.headline ? item.headline.toLowerCase().includes(lower) : false;
    const matchesGrade = item.verifiedGrade ? item.verifiedGrade.toLowerCase().includes(lower) : false;
    return matchesName || matchesHeadline || matchesGrade;
  });

  const handleSelect = (candidate: CandidateOption | null) => {
    onChange(candidate);
    setOpen(false);
    setQuery('');
    setHighlightedIndex(-1);
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (!open) {
      if (event.key === 'ArrowDown' || event.key === 'Enter') {
        event.preventDefault();
        setOpen(true);
      }
      return;
    }

    const totalOptions = filteredCandidates.length + (filterMode ? 1 : 0);

    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setHighlightedIndex((prev) => (prev + 1) % totalOptions);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setHighlightedIndex((prev) => (prev <= 0 ? totalOptions - 1 : prev - 1));
    } else if (event.key === 'Enter') {
      event.preventDefault();
      if (filterMode && highlightedIndex === 0) {
        handleSelect(null);
      } else {
        const optionIndex = filterMode ? highlightedIndex - 1 : highlightedIndex;
        if (optionIndex >= 0 && optionIndex < filteredCandidates.length) {
          handleSelect(filteredCandidates[optionIndex]);
        }
      }
    } else if (event.key === 'Escape') {
      event.preventDefault();
      setOpen(false);
    }
  };

  // If in readOnly mode (e.g. editing an existing record), show fixed candidate card
  if (readOnly && currentSelected) {
    return (
      <div className={`candidate-selected-card candidate-readonly-card ${className}`.trim()}>
        <CandidateAvatar name={currentSelected.displayName} size={36} />
        <div className="candidate-selected-info">
          <div className="candidate-selected-name">
            <strong>{currentSelected.displayName}</strong>
            {currentSelected.verifiedGrade && (
              <span className="badge employer-badge employer-badge-good">
                ✓ {currentSelected.verifiedGrade}
              </span>
            )}
            {currentSelected.demo && (
              <span className="badge employer-badge employer-badge-warm">DEMO</span>
            )}
          </div>
          {currentSelected.headline && (
            <div className="candidate-selected-headline">{currentSelected.headline}</div>
          )}
          <code className="candidate-selected-id">{currentSelected.id}</code>
        </div>
      </div>
    );
  }

  // If a candidate is already chosen in non-filter mode (editor modal), show card with "Сменить"
  if (!filterMode && currentSelected && !open) {
    return (
      <div className={`candidate-selected-card ${className}`.trim()}>
        <CandidateAvatar name={currentSelected.displayName} size={36} />
        <div className="candidate-selected-info">
          <div className="candidate-selected-name">
            <strong>{currentSelected.displayName}</strong>
            {currentSelected.verifiedGrade && (
              <span className="badge employer-badge employer-badge-good">
                ✓ {currentSelected.verifiedGrade}
              </span>
            )}
            {currentSelected.demo && (
              <span className="badge employer-badge employer-badge-warm">DEMO</span>
            )}
          </div>
          {currentSelected.headline && (
            <div className="candidate-selected-headline">{currentSelected.headline}</div>
          )}
          <code className="candidate-selected-id">{currentSelected.id}</code>
        </div>
        {!disabled && (
          <button
            type="button"
            className="button button-secondary button-small"
            onClick={() => {
              setOpen(true);
              setTimeout(() => inputRef.current?.focus(), 50);
            }}
          >
            Сменить кандидата
          </button>
        )}
      </div>
    );
  }

  return (
    <div ref={containerRef} className={`candidate-autocomplete-container ${className}`.trim()}>
      <div className="candidate-autocomplete-input-wrap">
        <span className="candidate-search-icon" aria-hidden="true">
          🔍
        </span>
        <input
          ref={inputRef}
          id={inputId}
          type="text"
          className="candidate-autocomplete-input"
          placeholder={currentSelected && filterMode ? currentSelected.displayName : placeholder}
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            if (!open) setOpen(true);
            setHighlightedIndex(-1);
          }}
          onFocus={() => setOpen(true)}
          onKeyDown={handleKeyDown}
          disabled={disabled}
          required={required && !value}
          autoComplete="off"
          role="combobox"
          aria-autocomplete="list"
          aria-expanded={open}
          aria-controls={listboxId}
        />
        {allowClear && (value || query) && !disabled && (
          <button
            type="button"
            className="candidate-autocomplete-clear-btn"
            onClick={() => {
              handleSelect(null);
              setQuery('');
            }}
            title="Очистить выбор"
            aria-label="Очистить выбор"
          >
            ×
          </button>
        )}
      </div>

      {open && (
        <ul
          id={listboxId}
          role="listbox"
          className="candidate-autocomplete-dropdown"
          aria-label="Список кандидатов"
        >
          {filterMode && (
            <li
              role="option"
              aria-selected={!value}
              className={`candidate-option ${highlightedIndex === 0 ? 'highlighted' : ''} ${!value ? 'selected' : ''}`}
              onClick={() => handleSelect(null)}
              onMouseEnter={() => setHighlightedIndex(0)}
            >
              <div className="candidate-option-name">
                <strong>Все кандидаты</strong>
              </div>
              <small className="candidate-option-sub">Без фильтрации по конкретному кандидату</small>
            </li>
          )}

          {loading && candidates.length === 0 && (
            <li className="candidate-option-empty" role="status">
              Загрузка кандидатов…
            </li>
          )}

          {candidateLoadError && (
            <li className="candidate-option-empty" role="alert">
              <span>{candidateLoadError}</span>{' '}
              <button type="button" className="button button-secondary button-small" onClick={retryCandidateSearch}>
                Повторить
              </button>
            </li>
          )}

          {!loading && !candidateLoadError && filteredCandidates.length === 0 && (
            <li className="candidate-option-empty" role="status">
              Кандидаты не найдены
            </li>
          )}

          {filteredCandidates.map((candidate, idx) => {
            const actualIndex = filterMode ? idx + 1 : idx;
            const isSelected = candidate.id === value;
            const isHighlighted = highlightedIndex === actualIndex;

            return (
              <li
                key={candidate.id}
                role="option"
                aria-selected={isSelected}
                className={`candidate-option ${isHighlighted ? 'highlighted' : ''} ${isSelected ? 'selected' : ''}`}
                onClick={() => handleSelect(candidate)}
                onMouseEnter={() => setHighlightedIndex(actualIndex)}
              >
                <CandidateAvatar name={candidate.displayName} size={28} />
                <div className="candidate-option-content">
                  <div className="candidate-option-name">
                    <span>{candidate.displayName}</span>
                    {candidate.verifiedGrade && (
                      <span className="badge employer-badge employer-badge-good">
                        ✓ {candidate.verifiedGrade}
                      </span>
                    )}
                    {candidate.demo && (
                      <span className="badge employer-badge employer-badge-warm">DEMO</span>
                    )}
                  </div>
                  {candidate.headline && (
                    <small className="candidate-option-sub">{candidate.headline}</small>
                  )}
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
