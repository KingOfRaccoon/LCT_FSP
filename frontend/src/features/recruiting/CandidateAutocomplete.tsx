import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { api, errorMessage, type Candidate, type Schemas } from '../../api';

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
  existingRecords = [],
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

  const containerRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  // Load public candidates from candidate bank
  useEffect(() => {
    let active = true;
    setLoading(true);
    setError('');

    const payload = {
      specialization_id: null,
      grade: null,
      category_status: 'any',
      skill_ids: [],
      fsp_status: 'any',
      limit: 50,
      cursor: null,
    };

    api.post<{ items: Candidate[] }>('/candidates/search', payload)
      .then((response) => {
        if (!active) return;
        const fetchedOptions: CandidateOption[] = (response.items || []).map((c) => ({
          id: c.id,
          displayName: c.display_name,
          headline: c.headline,
          verifiedGrade: c.verified_grade,
          declaredGrade: c.declared_grade,
          visibility: 'public',
          demo: Boolean(c.demo),
        }));

        // Merge candidates from existing records
        const recordCandidates: CandidateOption[] = existingRecords
          .filter((rec) => rec.candidate_id && rec.candidate_display_name)
          .map((rec) => ({
            id: rec.candidate_id,
            displayName: rec.candidate_display_name || 'Профиль скрыт',
            headline: null,
            visibility: rec.candidate_visibility,
            demo: false,
          }));

        // Deduplicate by ID
        const map = new Map<string, CandidateOption>();
        fetchedOptions.forEach((c) => map.set(c.id, c));
        recordCandidates.forEach((c) => {
          if (!map.has(c.id)) {
            map.set(c.id, c);
          }
        });

        setCandidates(Array.from(map.values()));
      })
      .catch((err: unknown) => {
        if (!active) return;
        // If candidate search is restricted or fails, still use existing records
        const recordCandidates: CandidateOption[] = existingRecords
          .filter((rec) => rec.candidate_id)
          .map((rec) => ({
            id: rec.candidate_id,
            displayName: rec.candidate_display_name || 'Кандидат',
            headline: null,
            visibility: rec.candidate_visibility,
            demo: false,
          }));

        if (recordCandidates.length > 0) {
          const map = new Map<string, CandidateOption>();
          recordCandidates.forEach((c) => map.set(c.id, c));
          setCandidates(Array.from(map.values()));
        } else {
          setError(errorMessage(err));
        }
      })
      .finally(() => {
        if (active) setLoading(false);
      });

    return () => {
      active = false;
    };
  }, [existingRecords]);

  // Derive currently chosen candidate
  const currentSelected: CandidateOption | null =
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
  const filteredCandidates = candidates.filter((item) => {
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

          {error && candidates.length === 0 && (
            <li className="candidate-option-empty" role="status">
              {error}
            </li>
          )}

          {!loading && filteredCandidates.length === 0 && (
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
