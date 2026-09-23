import type { MetadataRoute } from "next";

export default function robots(): MetadataRoute.Robots {
  return {
    rules: {
      userAgent: "*",
      allow: "/",
      disallow: [
        "/admin",
        "/api",
        "/dashboard",
        "/ipo",
        "/landing",
        "/login",
        "/mypage",
        "/news",
        "/ranking",
        "/review",
      ],
    },
    sitemap: "https://flashback.my/sitemap.xml",
  };
}
