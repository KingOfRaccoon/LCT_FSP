import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import ts from 'typescript';

// Deterministic hook/JSX host for real component callbacks. Browser smoke tests
// separately cover React rendering and effects; HTTP is injected at the boundary.
export function componentHarness(file, name, mocks = {}) {
  const originalDocument = globalThis.document;
  if (!originalDocument) globalThis.document = { addEventListener() {}, removeEventListener() {} };
  const slots = [];
  const modules = new Map();
  let index = 0;
  let dirty = false;
  let pendingEffects = [];
  let tree;
  let props;
  let mounted = true;
  let idSequence = 0;
  const changed = (before, after) => !before || !after || before.length !== after.length || after.some((value, i) => !Object.is(value, before[i]));
  const react = {
    useState(initial) {
      const position = index++;
      slots[position] ??= { value: typeof initial === 'function' ? initial() : initial };
      return [slots[position].value, (value) => {
        if (!mounted) return;
        const next = typeof value === 'function' ? value(slots[position].value) : value;
        if (!Object.is(next, slots[position].value)) { slots[position].value = next; dirty = true; }
      }];
    },
    useRef(initial) {
      const position = index++;
      slots[position] ??= { current: initial };
      return slots[position];
    },
    useId() {
      const position = index++;
      slots[position] ??= { value: `test-id-${++idSequence}` };
      return slots[position].value;
    },
    useCallback(callback, deps) {
      const position = index++;
      if (changed(slots[position]?.deps, deps)) slots[position] = { value: callback, deps };
      return slots[position].value;
    },
    useEffect(effect, deps) {
      const position = index++;
      const previous = slots[position];
      if (changed(previous?.deps, deps)) {
        slots[position] = { deps, cleanup: previous?.cleanup };
        pendingEffects.push(() => { previous?.cleanup?.(); slots[position].cleanup = effect(); });
      }
    },
  };
  const jsx = (type, values, key) => ({ type, props: values ?? {}, key });
  const runtime = { jsx, jsxs: jsx, Fragment: 'fragment' };
  function load(filename) {
    if (modules.has(filename)) return modules.get(filename).exports;
    const module = { exports: {} };
    modules.set(filename, module);
    const localRequire = createRequire(filename);
    const require = (specifier) => {
      if (Object.hasOwn(mocks, specifier)) return mocks[specifier];
      if (specifier === 'react') return react;
      if (specifier === 'react/jsx-runtime') return runtime;
      if (specifier.endsWith('.css')) return {};
      if (!specifier.startsWith('.')) return localRequire(specifier);
      const base = path.resolve(path.dirname(filename), specifier);
      if (Object.hasOwn(mocks, base)) return mocks[base];
      const target = [base, `${base}.ts`, `${base}.tsx`, `${base}/index.ts`].find((value) => fs.existsSync(value) && fs.statSync(value).isFile());
      if (!target) throw new Error(`Unresolved test import: ${specifier}`);
      return load(target);
    };
    const source = ts.transpileModule(fs.readFileSync(filename, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX } }).outputText;
    vm.runInThisContext(`(function(require, module, exports) {\n${source}\n})`, { filename })(require, module, module.exports);
    return module.exports;
  }
  const Component = load(file)[name];
  function render(nextProps = props) {
    props = nextProps; index = 0; dirty = false;
    tree = Component(props);
    const effects = pendingEffects; pendingEffects = [];
    effects.forEach((effect) => effect());
    return tree;
  }
  return {
    render,
    get tree() { return tree; },
    async flush() {
      for (let i = 0; i < 12; i++) { await Promise.resolve(); if (dirty) render(); }
      return tree;
    },
    unmount() {
      mounted = false;
      slots.forEach((slot) => slot?.cleanup?.());
      if (!originalDocument) delete globalThis.document;
    },
  };
}

export function nodes(tree, predicate = () => true) {
  if (!tree || typeof tree !== 'object') return [];
  if (Array.isArray(tree)) return tree.flatMap((item) => nodes(item, predicate));
  return [...(predicate(tree) ? [tree] : []), ...nodes(tree.props?.children, predicate)];
}

export function textContent(tree) {
  if (tree == null || typeof tree === 'boolean') return '';
  if (typeof tree !== 'object') return String(tree);
  if (Array.isArray(tree)) return tree.map(textContent).join('');
  return textContent(tree.props?.children);
}

export function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
