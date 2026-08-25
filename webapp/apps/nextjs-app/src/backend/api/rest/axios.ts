import { createAxios } from '@teable/openapi';

export const getAxios = () => {
  const axios = createAxios();
  // Server-side rendering reads from the same backend the browser is proxied to, rather
  // than from this process, which no longer serves the API.
  axios.defaults.baseURL =
    process.env.TEABLE_API_ORIGIN
      ? `${process.env.TEABLE_API_ORIGIN}/api`
      : `http://localhost:${process.env.PORT}/api`;
  return axios;
};

export const axios = getAxios();
