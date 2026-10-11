import React from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router';
import App from './App';
import { ThemeProvider } from './theme';
import './styles.css';

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: 1, staleTime: 15_000, refetchOnWindowFocus: false }, mutations: { retry: false } } });

class GlobalErrorBoundary extends React.Component<{ children: React.ReactNode }, { hasError: boolean }> {
  constructor(props: { children: React.ReactNode }) {
    super(props);
    this.state = { hasError: false };
  }
  static getDerivedStateFromError() {
    return { hasError: true };
  }
  componentDidCatch(error: Error, errorInfo: React.ErrorInfo) {
    console.error('Unhandled UI Error:', error, errorInfo);
  }
  render() {
    if (this.state.hasError) {
      return (
        <div style={{ padding: '2rem', maxWidth: '640px', margin: '4rem auto', fontFamily: 'system-ui, sans-serif' }}>
          <h2 style={{ color: '#d9534f' }}>Произошла ошибка при отображении интерфейса</h2>
          <pre style={{ background: '#f8f9fa', color: '#111', padding: '1rem', borderRadius: '6px', overflowX: 'auto', border: '1px solid #ddd', whiteSpace: 'pre-wrap' }}>
            Не удалось отобразить эту страницу. Попробуйте обновить приложение.
          </pre>
          <button
            style={{ marginTop: '1rem', padding: '0.6rem 1.2rem', cursor: 'pointer', background: '#0284c7', color: '#fff', border: 'none', borderRadius: '4px' }}
            onClick={() => { window.location.href = '/'; }}
          >
            Вернуться на главную
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <GlobalErrorBoundary>
      <ThemeProvider>
        <QueryClientProvider client={queryClient}>
          <BrowserRouter><App /></BrowserRouter>
        </QueryClientProvider>
      </ThemeProvider>
    </GlobalErrorBoundary>
  </React.StrictMode>,
);
