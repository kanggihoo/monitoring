import type { MetadataRoute } from "next";

export default function sitemap(): MetadataRoute.Sitemap {
  return [
    {
      url: "https://flashback.my",
      changeFrequency: "daily",
      priority: 1,
    },
  ];
}
