import { writeFileSync } from 'node:fs';
import { generateOpenApi, OPENAPI_PATH } from './openapi.js';

writeFileSync(OPENAPI_PATH, await generateOpenApi());
console.log(`wrote ${OPENAPI_PATH}`);
