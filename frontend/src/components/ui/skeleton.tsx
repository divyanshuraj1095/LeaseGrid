import type { ComponentProps } from "react";
export function Skeleton({ className = "", ...props }: ComponentProps<"div">) {
  return (
    <div
      data-slot="skeleton"
      className={"animate-pulse rounded-md bg-slate-500/20 " + className}
      {...props}
    />
  );
}
