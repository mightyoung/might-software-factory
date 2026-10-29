export function toggleVisibleRows<T extends string>(
  selected: ReadonlySet<T>,
  visibleIds: readonly T[],
): Set<T> {
  const next = new Set(selected);
  const allVisibleSelected = visibleIds.length > 0 && visibleIds.every((id) => next.has(id));

  for (const id of visibleIds) {
    if (allVisibleSelected) next.delete(id);
    else next.add(id);
  }

  return next;
}

export function toggleRow<T extends string>(selected: ReadonlySet<T>, id: T): Set<T> {
  const next = new Set(selected);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  return next;
}
