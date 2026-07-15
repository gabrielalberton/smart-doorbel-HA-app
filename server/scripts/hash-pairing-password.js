#!/usr/bin/env node
const crypto = require('node:crypto');
const readline = require('node:readline');
const { execFileSync } = require('node:child_process');

if (!process.stdin.isTTY) {
  console.error('Run this script in an interactive terminal so the password is not stored in shell history.');
  process.exit(2);
}

const rl = readline.createInterface({ input: process.stdin, output: process.stderr, terminal: true });
let echoDisabled = false;
try {
  execFileSync('stty', ['-echo'], { stdio: ['inherit', 'ignore', 'ignore'] });
  echoDisabled = true;
} catch {
  rl.close();
  console.error('Could not disable terminal echo; refusing to read a password.');
  process.exit(2);
}

rl.question('Pairing password: ', (answer) => {
  if (echoDisabled) execFileSync('stty', ['echo'], { stdio: ['inherit', 'ignore', 'ignore'] });
  process.stderr.write('\n');
  rl.close();
  if (!answer) {
    console.error('Password cannot be empty.');
    process.exit(2);
  }
  const salt = crypto.randomBytes(16);
  const derived = crypto.scryptSync(answer, salt, 32);
  answer = '';
  process.stdout.write(`scrypt:${salt.toString('hex')}:${derived.toString('hex')}\n`);
});

process.on('exit', () => {
  if (echoDisabled) {
    try { execFileSync('stty', ['echo'], { stdio: ['inherit', 'ignore', 'ignore'] }); } catch {}
  }
});
