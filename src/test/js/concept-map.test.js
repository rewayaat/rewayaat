const test = require('node:test');
const assert = require('node:assert/strict');

const ConceptMapUtils = require('../../main/resources/static/js/concept-map.js');

test('buildConceptMapData includes rolled-up parent themes, child themes, and hierarchy edges', () => {
    const data = ConceptMapUtils.buildConceptMapData([
        { topic_tags: ['friday-prayer', 'charity'] },
        { topic_tags: ['night-prayer', 'charity'] },
        { topic_tags: ['fasting'] }
    ], {
        prayer: { category: 'worship' },
        'friday-prayer': { category: 'worship', parent: 'prayer' },
        'night-prayer': { category: 'worship', parent: 'prayer' },
        charity: { category: 'worship' },
        fasting: { category: 'worship' }
    }, 25, 2);

    assert.equal(data.nodes.length, 5);
    assert.deepEqual(data.nodes.find(function(node) { return node.id === 'prayer'; }), {
        id: 'prayer',
        count: 2,
        category: 'worship',
        parent: '',
        depth: 0
    });
    assert.deepEqual(data.nodes.find(function(node) { return node.id === 'friday-prayer'; }), {
        id: 'friday-prayer',
        count: 1,
        category: 'worship',
        parent: 'prayer',
        depth: 1
    });
    assert.ok(data.edges.some(function(edge) {
        return edge.source === 'prayer' && edge.target === 'friday-prayer' && edge.type === 'hierarchy';
    }));
    assert.ok(data.edges.some(function(edge) {
        return edge.source === 'prayer' && edge.target === 'night-prayer' && edge.type === 'hierarchy';
    }));
    assert.ok(data.edges.some(function(edge) {
        return edge.source === 'charity' && edge.target === 'prayer' && edge.weight === 2 && edge.type === 'cooccurrence';
    }));
});

test('buildConceptMapData prunes to requested node limit', () => {
    const narrations = [];
    for (let i = 0; i < 30; i += 1) {
        narrations.push({ topic_tags: ['tag-' + i] });
    }
    const data = ConceptMapUtils.buildConceptMapData(narrations, {}, 25, 2);
    assert.equal(data.nodes.length, 25);
    assert.equal(data.graphNodesPruned, true);
});

test('nextVisibleNarrationCount increments safely', () => {
    assert.equal(ConceptMapUtils.nextVisibleNarrationCount(16, 50, 12), 28);
    assert.equal(ConceptMapUtils.nextVisibleNarrationCount(45, 50, 12), 50);
});
