"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { isNavActive } from "@/lib/routes";

/** A header link that marks itself with aria-current while its section is open. */
export function NavLink({ href, section, children }: { href: string; section?: string; children: React.ReactNode }) {
  const pathname = usePathname();
  const current = isNavActive(section ?? href, pathname);
  return <Link href={href} prefetch={false} aria-current={current ? "page" : undefined}>{children}</Link>;
}
