import { defineConfig } from '@hey-api/openapi-ts';

export default defineConfig({
  input: './openapi.yaml',
  output: 'src/api/axios',
  plugins: [
    {
      name: '@hey-api/client-axios',
    },
    {
      name: '@hey-api/sdk',
    },
    {
      name: '@hey-api/typescript',
    },
  ],
});
