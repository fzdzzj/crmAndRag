declare module 'vue-page-review' {
  import type { Component, Ref } from 'vue';

  export interface ReviewToolProps {
    active?: boolean;
    pagePath?: string;
    pageName?: string;
    storageKey?: string;
    enableComponentTree?: boolean;
    enableZipExport?: boolean;
    imageUploadUrl?: string;
  }

  export const ReviewTool: Component<ReviewToolProps>;

  export interface UsePageReviewOptions {
    storageKey?: string;
  }

  export interface UsePageReviewReturn {
    reviews: Ref<unknown[]>;
    addReview: (review: unknown) => void;
    exportToJSON: () => string;
    exportToZIP: () => Promise<Blob>;
  }

  export function usePageReview(options?: UsePageReviewOptions): UsePageReviewReturn;
}

declare module 'vue-page-review/style.css' {}
