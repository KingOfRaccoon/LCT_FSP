import assert from 'node:assert/strict';
import test from 'node:test';
import { componentHarness, nodes, textContent } from './helpers/component-harness.mjs';

const modalPath = new URL('../src/features/extensions/SolutionEditorModal.tsx', import.meta.url).pathname;

test('solution editor applies a draft without claiming validation or delivering it', () => {
  const applied = [];
  const closed = [];
  const harness = componentHarness(modalPath, 'SolutionEditorModal');
  const props = {
    taskTitle: 'Retry task',
    taskDescription: 'Write a retry function.',
    initialCode: 'unvalidated draft',
    onClose: () => closed.push(true),
    onApplyToDraft: (code) => applied.push(code),
  };

  let tree = harness.render(props);
  const editor = nodes(tree, (node) => node.type === 'textarea')[0];
  assert.equal(editor.props.value, 'unvalidated draft');
  assert.equal(editor.props.maxLength, 20000);
  editor.props.onChange({ target: { value: '' } });
  tree = harness.render(props);
  const applyButton = nodes(tree, (node) => node.type === 'button').find((node) => /перенести|применить/i.test(textContent(node)));

  assert.ok(applyButton, 'editor has an apply-to-draft action');
  applyButton.props.onClick();

  assert.deepEqual(applied, ['']);
  assert.deepEqual(closed, [true]);
  assert.doesNotMatch(textContent(tree), /успешно пройдены|отправлены работодателю|запустить автотесты|sandbox/i);
  harness.unmount();
});

test('Escape cancels the editor and prevents the browser default', () => {
  const closed = [];
  const originalDocument = globalThis.document;
  const originalHTMLElement = globalThis.HTMLElement;
  class FocusTarget {
    isConnected = true;
    focused = false;
    focus() { this.focused = true; }
  }
  const opener = new FocusTarget();
  globalThis.HTMLElement = FocusTarget;
  globalThis.document = { activeElement: opener };
  const harness = componentHarness(modalPath, 'SolutionEditorModal');
  try {
    const tree = harness.render({
      taskTitle: 'Retry task',
      taskDescription: 'Write a retry function.',
      initialCode: 'draft',
      onClose: () => closed.push(true),
      onApplyToDraft: () => assert.fail('Escape must not apply the draft'),
    });
    const dialog = nodes(tree, (node) => node.props.role === 'dialog')[0];
    let prevented = false;
    dialog.props.onKeyDown({ key: 'Escape', preventDefault: () => { prevented = true; } });

    assert.equal(prevented, true);
    assert.deepEqual(closed, [true]);
    harness.unmount();
    assert.equal(opener.focused, true, 'closing restores focus to the opening control');
  } finally {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
    if (originalHTMLElement === undefined) delete globalThis.HTMLElement;
    else globalThis.HTMLElement = originalHTMLElement;
  }
});

test('Tab stays within the editor dialog', () => {
  const originalDocument = globalThis.document;
  globalThis.document = { activeElement: {} };
  const harness = componentHarness(modalPath, 'SolutionEditorModal');
  try {
    const tree = harness.render({
      taskTitle: 'Retry task',
      taskDescription: 'Write a retry function.',
      initialCode: '',
      onClose: () => {},
      onApplyToDraft: () => {},
    });
    const dialog = nodes(tree, (node) => node.props.role === 'dialog')[0];
    let firstFocused = false;
    let lastFocused = false;
    let prevented = false;
    const first = { focus: () => { firstFocused = true; } };
    const last = { focus: () => { lastFocused = true; } };
    dialog.props.ref.current = { querySelectorAll: () => [first, last], contains: () => false };

    dialog.props.onKeyDown({ key: 'Tab', shiftKey: false, preventDefault: () => { prevented = true; } });

    assert.equal(prevented, true);
    assert.equal(firstFocused, true);
    assert.equal(lastFocused, false);
    harness.unmount();
  } finally {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
  }
});

test('solution editor transfers invalid code as an unvalidated draft without delivering it', () => {
  const applied = [];
  const closed = [];
  const harness = componentHarness(modalPath, 'SolutionEditorModal');
  const props = {
    taskTitle: 'Retry task',
    taskDescription: 'Write a retry function.',
    initialCode: '',
    onClose: () => closed.push(true),
    onApplyToDraft: (code) => applied.push(code),
  };

  let tree = harness.render(props);
  const editor = nodes(tree, (node) => node.type === 'textarea')[0];
  editor.props.onChange({ target: { value: 'this is not valid Python' } });
  tree = harness.render(props);
  const applyButton = nodes(tree, (node) => node.type === 'button').find((node) => /перенести|применить/i.test(textContent(node)));

  applyButton.props.onClick();

  assert.deepEqual(applied, ['this is not valid Python']);
  assert.deepEqual(closed, [true]);
  assert.doesNotMatch(textContent(tree), /успешно пройдены|отправлено работодателю|отправлены работодателю|результаты тестов|sandbox/i);
  harness.unmount();
});

