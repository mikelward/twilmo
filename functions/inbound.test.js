// Handler-level tests for inbound.protected.js, run with Node's built-in
// runner (`node --test functions`). The Twilio runtime globals are stubbed;
// node --test isolates each test file in its own process, so this global
// never collides with token.test.js's.

const test = require('node:test');
const assert = require('node:assert/strict');

class FakeDial {
  constructor(opts) {
    this.opts = opts;
    this.clients = [];
  }

  client(identity) {
    this.clients.push(identity);
  }
}

class FakeVoiceResponse {
  constructor() {
    this.dials = [];
  }

  dial(opts) {
    const dial = new FakeDial(opts);
    this.dials.push(dial);
    return dial;
  }
}

global.Twilio = { twiml: { VoiceResponse: FakeVoiceResponse } };

const { handler } = require('./inbound.protected.js');

test('dials exactly the client identity, bridged, with the ring timeout', async () => {
  const twiml = await new Promise((resolve, reject) => {
    handler({ CLIENT_IDENTITY: 'twilmo' }, {}, (err, out) =>
      err ? reject(err) : resolve(out),
    );
  });
  assert.equal(twiml.dials.length, 1);
  const dial = twiml.dials[0];
  // answerOnBridge keeps the caller hearing ringback while the phone wakes
  // and keeps unanswered pushes from billing a connected leg.
  assert.deepEqual(dial.opts, { answerOnBridge: true, timeout: 30 });
  assert.deepEqual(dial.clients, ['twilmo']);
});
