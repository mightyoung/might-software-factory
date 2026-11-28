import { act, render, screen, waitFor } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { navigate, useLocation } from "./router";

function Location() {
  return <output data-testid="location">{useLocation()}</output>;
}

it("keeps the raw query and replacement history without faking a native popstate", async () => {
  const first = "/t/demo/safety/findings?tag=a&tag=b&empty=&q=a+b";
  const destination = "/t/demo/platform/ledgers?q=%E4%B8%AD&x=%2f&x=%2F";
  window.history.replaceState(null, "", first);
  const pop = vi.fn();
  window.addEventListener("popstate", pop);
  try {
    render(<Location />);
    expect(screen.getByTestId("location")).toHaveTextContent(first);
    act(() => navigate("/t/demo/platform/permissions"));
    act(() => navigate(destination, true));
    expect(screen.getByTestId("location")).toHaveTextContent(destination);
    expect(location.pathname + location.search).toBe(destination);
    expect(pop).not.toHaveBeenCalled();
    act(() => window.history.back());
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent(first));
    expect(pop).toHaveBeenCalledTimes(1);
  } finally {
    window.removeEventListener("popstate", pop);
  }
});
