# Tether — instructions for Claude

## Comments

Avoid inline comments (`//` or `/* */` inside function bodies). They drift out of date as the code changes and end up saying things that are no longer true.

Instead:

- **Let the language carry the meaning.** Use descriptive names, small well-named functions, types, sealed classes, enums, value classes, named arguments and constants so the code explains itself. If you feel a comment is needed to explain a block, extract that block into a function whose name says what it does.
- **Document declarations with KDoc** (`/** ... */`, or Javadoc/JSDoc in other languages) on classes, functions and properties. Describe what it does, why it exists and any contract (parameters, return value, threading, side effects), not how the body is written line by line.
- **Keep KDoc current.** When you change a declaration's behaviour, update its KDoc in the same change.
- **Don't add inline comments to new code**, and when editing code that has them, prefer turning them into better names, extracted functions or KDoc over leaving or adding to them.

An inline comment is acceptable only when the language can't express the point and it has no natural home in a doc comment, such as a link to an upstream bug that justifies a workaround. Keep those rare and short.
