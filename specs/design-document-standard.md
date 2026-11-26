# Appbase design document standard

Every new or substantially revised module design document must contain:

1. Purpose, responsibilities, non-goals and dependencies.
2. Design principles and rationale: architecture, ownership, data/control flow, alternatives and trade-offs.
3. Public API: signatures, semantics, inputs/outputs, error behavior and representative usage.
4. Implementation principles: lifecycle, concurrency, resource limits, security, platform boundaries and performance where applicable.
5. Potential problems and solutions: failure modes, edge cases, mitigations, remaining risks and explicit decisions. Distinguish implemented mitigations from proposed work.
6. Acceptance criteria and verification: tests, compilation, integration, platform validation and actual status.
7. Source locations and known limitations.

Keep specifications concise but sufficient for implementation and review. Describe requirements directly, without references to inspiration frameworks. Maintain consistent design and interface semantics across independently implemented native platforms; document platform differences explicitly. Never present proposed features or unexecuted tests as completed. Update the module document when public behavior or architectural decisions change.
