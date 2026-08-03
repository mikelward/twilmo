// Handler-level tests for outbound.protected.js, run with Node's built-in
// runner (`node --test functions/*.test.js`). The Twilio runtime globals
// are stubbed; each test file runs in its own process. Numbers are the
// documentation-range stand-ins, nobody's real number.

const test = require('node:test');
const assert = require('node:assert/strict');

class FakeDial {
  constructor(opts) {
    this.opts = opts;
    this.numbers = [];
  }

  number(value) {
    this.numbers.push(value);
  }
}

class FakeVoiceResponse {
  constructor() {
    this.dials = [];
    this.hangups = 0;
  }

  dial(opts) {
    const dial = new FakeDial(opts);
    this.dials.push(dial);
    return dial;
  }

  hangup() {
    this.hangups += 1;
  }
}

global.Twilio = { twiml: { VoiceResponse: FakeVoiceResponse } };

const { handler } = require('./outbound.protected.js');

function call(event) {
  return new Promise((resolve, reject) => {
    handler({ CALLER_ID: '+15550100' }, event, (err, out) =>
      err ? reject(err) : resolve(out),
    );
  });
}

test('dials the To number with the account caller ID', async () => {
  const twiml = await call({ To: '+15550199' });
  assert.equal(twiml.dials.length, 1);
  assert.deepEqual(twiml.dials[0].opts, { callerId: '+15550100' });
  assert.deepEqual(twiml.dials[0].numbers, ['+15550199']);
  assert.equal(twiml.hangups, 0);
});

test('a missing To ends the leg instead of dialing nothing', async () => {
  const twiml = await call({});
  assert.equal(twiml.dials.length, 0);
  assert.equal(twiml.hangups, 1);
});
