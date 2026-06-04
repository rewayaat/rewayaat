const test = require('node:test');
const assert = require('node:assert/strict');

global.document = {};
global.window = { location: { search: '' } };
global.$ = function() {
    return {
        ready() {},
        resize() {}
    };
};

const searchMode = require('../../main/resources/static/js/rewayaat.js');

test('resolveSearchMatchModeValue prefers explicit url mode', () => {
    assert.equal(searchMode.resolveSearchMatchModeValue('precise', 'flexible'), 'precise');
    assert.equal(searchMode.resolveSearchMatchModeValue('flexible', 'precise'), 'flexible');
});

test('resolveSearchMatchModeValue falls back to current mode', () => {
    assert.equal(searchMode.resolveSearchMatchModeValue('', 'flexible'), 'flexible');
    assert.equal(searchMode.resolveSearchMatchModeValue(null, 'precise'), 'precise');
});

test('normalizeSearchMatchMode accepts legacy aliases but emits canonical values', () => {
    assert.equal(searchMode.normalizeSearchMatchMode('permissive'), 'flexible');
    assert.equal(searchMode.normalizeSearchMatchMode('strict'), 'precise');
    assert.equal(searchMode.normalizeSearchMatchMode('exact'), 'precise');
});

test('getSearchModeLabel matches strictness labels', () => {
    assert.equal(searchMode.getSearchModeLabel('precise'), 'Precise');
    assert.equal(searchMode.getSearchModeLabel('flexible'), 'Flexible');
    assert.equal(searchMode.getSearchModeLabel('garbage'), 'Flexible');
});

test('shouldTriggerSearchOnEnter blocks submit when a term is still being composed', () => {
    assert.equal(searchMode.shouldTriggerSearchOnEnter(['anger'], 0, 1000), false);
});

test('shouldTriggerSearchOnEnter blocks submit during the keyboard-commit guard window', () => {
    assert.equal(searchMode.shouldTriggerSearchOnEnter([], 1500, 1200), false);
});

test('shouldTriggerSearchOnEnter allows submit once there is no pending term or guard', () => {
    assert.equal(searchMode.shouldTriggerSearchOnEnter([], 900, 1200), true);
});
