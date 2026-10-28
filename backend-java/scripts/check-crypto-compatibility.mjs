import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createDecipheriv} from 'node:crypto';
import argon2 from 'argon2';

const path=new URL('../target/java-crypto-fixture.json',import.meta.url);
const fixture=JSON.parse(await readFile(path,'utf8'));
assert.ok(await argon2.verify(fixture.passwordHash,fixture.password));
const wire=Buffer.from(fixture.ciphertext,'base64');
const cipher=createDecipheriv('aes-256-gcm',Buffer.from(fixture.key,'hex'),wire.subarray(0,12));
cipher.setAuthTag(wire.subarray(12,28));
assert.equal(Buffer.concat([cipher.update(wire.subarray(28)),cipher.final()]).toString('utf8'),fixture.text);
process.stdout.write('Java/Node compatibility verified in both directions for synthetic Argon2id and AES-GCM data.\n');
