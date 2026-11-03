import { loadConfig } from '../config.js';
import { openDatabase } from './index.js';
import { migrate } from './migrate.js';

const config = loadConfig();
const database = await openDatabase(config.databaseUrl);
try {
  const ran = await migrate(database);
  console.log(ran.length ? `applied: ${ran.join(', ')}` : 'database is up to date');
} finally {
  await database.close();
}
