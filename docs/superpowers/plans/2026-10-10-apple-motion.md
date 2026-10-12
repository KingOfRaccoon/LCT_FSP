# Apple-inspired motion integration plan

- Install the shared press-motion runtime once in App with an unconditional useEffect before existing early returns; preserve the current route tree and all keys/state behavior.
- Import a dedicated motion stylesheet from styles.css. Define shared short durations/easing, eligible native button/link press visuals with an inverse-scaled pseudo hit plane, and focused entrance effects for header text, existing modal backdrop/panels, notices/errors, and the mobile details caret.
- Keep dialogs, cards, queue rows, forms, route/workspace ancestors, and action buttons inside headers out of entrance motion. Reduced motion disables all new movement both through the native media query and runtime root flag while retaining static opacity/color/focus feedback.
- Review selectors against existing modal, notice, header, and mobile disclosure markup; preserve existing styles and focus semantics. Record source-boundary evidence and diff checks under local/apple-motion/2026-10-10.
- Verification: targeted stylesheet/source review and git diff --check; parent owns browser/device checks and full project verification after runtime integration.
