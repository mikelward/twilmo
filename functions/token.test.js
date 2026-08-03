// Handler-level tests for token.js, run with Node's built-in runner
// (`node --test functions`) — no dependencies, and CI runs it alongside the
// Gradle suite. The Twilio runtime globals are stubbed; every value below is
// an obviously-fake placeholder.

const test = require('node:test');
const assert = require('node:assert/strict');

class FakeVoiceGrant {
  constructor(opts) {
    this.opts = opts;
  }
}

class FakeAccessToken {
  constructor(accountSid, keySid, keySecret, opts) {
    this.args = { accountSid, keySid, keySecret, opts };
    this.grants = [];
    FakeAccessToken.instances.push(this);
  }

  addGrant(grant) {
    this.grants.push(grant);
  }

  toJwt() {
    return 'fake-jwt';
  }
}
FakeAccessToken.instances = [];
FakeAccessToken.VoiceGrant = FakeVoiceGrant;

class FakeResponse {
  constructor() {
    this.statusCode = 200;
    this.headers = {};
    this.body = null;
  }

  setStatusCode(code) {
    this.statusCode = code;
  }

  appendHeader(key, value) {
    this.headers[key] = value;
  }

  setBody(body) {
    this.body = body;
  }
}

global.Twilio = { jwt: { AccessToken: FakeAccessToken }, Response: FakeResponse };

const { handler } = require('./token.js');

const context = {
  ACCOUNT_SID: 'ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx',
  API_KEY_SID: 'SKxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx',
  API_KEY_SECRET: 'not-a-real-key-secret',
  TWIML_APP_SID: 'APxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx',
  PUSH_CREDENTIAL_SID: 'CRxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx',
  CLIENT_IDENTITY: 'twilmo',
  SHARED_SECRET: 'not-a-real-shared-secret',
};

function call(headers, ctx = context) {
  return new Promise((resolve, reject) => {
    handler(ctx, { request: { headers } }, (err, res) => {
      if (err) reject(err);
      else resolve(res);
    });
  });
}

test('the right secret mints a token with the pinned response shape', async () => {
  const res = await call({ 'x-twilmo-secret': context.SHARED_SECRET });
  assert.equal(res.statusCode, 200);
  assert.equal(res.headers['Content-Type'], 'application/json');
  const body = JSON.parse(res.body);
  assert.equal(body.token, 'fake-jwt');
  assert.equal(body.expiresInSeconds, 3600);
  assert.equal(body.identity, 'twilmo');
});

test('the token carries identity, TTL, and all three voice grants', async () => {
  FakeAccessToken.instances.length = 0;
  await call({ 'x-twilmo-secret': context.SHARED_SECRET });
  assert.equal(FakeAccessToken.instances.length, 1);
  const minted = FakeAccessToken.instances[0];
  assert.equal(minted.args.accountSid, context.ACCOUNT_SID);
  assert.equal(minted.args.keySid, context.API_KEY_SID);
  assert.equal(minted.args.keySecret, context.API_KEY_SECRET);
  assert.deepEqual(minted.args.opts, { identity: 'twilmo', ttl: 3600 });
  assert.equal(minted.grants.length, 1);
  // incomingAllow is what lets Voice.register accept the identity for
  // incoming calls — losing it silently breaks the ring (Phase 3).
  assert.deepEqual(minted.grants[0].opts, {
    outgoingApplicationSid: context.TWIML_APP_SID,
    incomingAllow: true,
    pushCredentialSid: context.PUSH_CREDENTIAL_SID,
  });
});

test('a wrong secret gets 401 with no detail', async () => {
  const res = await call({ 'x-twilmo-secret': 'wrong' });
  assert.equal(res.statusCode, 401);
  assert.deepEqual(JSON.parse(res.body), { error: 'unauthorized' });
});

test('a missing secret header gets 401', async () => {
  const res = await call({});
  assert.equal(res.statusCode, 401);
});

test('a missing request object gets 401 rather than a crash', async () => {
  const res = await new Promise((resolve, reject) => {
    handler(context, {}, (err, out) => (err ? reject(err) : resolve(out)));
  });
  assert.equal(res.statusCode, 401);
});

test('an unset SHARED_SECRET rejects everything, even an empty match', async () => {
  const ctx = { ...context, SHARED_SECRET: '' };
  const res = await call({ 'x-twilmo-secret': '' }, ctx);
  assert.equal(res.statusCode, 401);
});
