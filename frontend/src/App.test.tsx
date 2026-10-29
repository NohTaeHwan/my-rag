import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, test } from 'vitest';
import App from './App';

afterEach(cleanup);

test('제품 이름과 기본 지식 공간 안내를 표시한다', () => {
  render(<App />);

  expect(screen.getByRole('heading', { level: 1, name: 'my-rag' })).toBeTruthy();
  expect(screen.getByText('기본 지식 공간')).toBeTruthy();
});
