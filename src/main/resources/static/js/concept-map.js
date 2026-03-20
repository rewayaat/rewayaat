(function(root, factory) {
    if (typeof module === 'object' && module.exports) {
        module.exports = factory();
        return;
    }
    root.ConceptMapUtils = factory();
}(typeof self !== 'undefined' ? self : this, function() {
    var DEFAULT_NODE_LIMIT = 25;
    var DEFAULT_EDGE_THRESHOLD = 2;

    function clampPositiveInt(value, fallback) {
        var num = Number(value);
        if (isNaN(num) || num < 1) {
            return fallback;
        }
        return Math.floor(num);
    }

    function uniqueTags(tags) {
        var seen = {};
        return (Array.isArray(tags) ? tags : []).filter(function(tag) {
            var normalized = String(tag || '').trim();
            if (!normalized || seen[normalized]) {
                return false;
            }
            seen[normalized] = true;
            return true;
        }).map(function(tag) {
            return String(tag || '').trim();
        });
    }

    function ancestorTagsFor(tag, taxonomy) {
        var ancestors = [];
        var current = taxonomy && taxonomy[tag] ? taxonomy[tag].parent : '';
        while (current) {
            ancestors.push(current);
            current = taxonomy && taxonomy[current] ? taxonomy[current].parent : '';
        }
        return ancestors;
    }

    function expandedTagSet(tags, taxonomy) {
        var expanded = [];
        uniqueTags(tags).forEach(function(tag) {
            if (expanded.indexOf(tag) === -1) {
                expanded.push(tag);
            }
            ancestorTagsFor(tag, taxonomy).forEach(function(ancestor) {
                if (expanded.indexOf(ancestor) === -1) {
                    expanded.push(ancestor);
                }
            });
        });
        return expanded;
    }

    function depthFor(tag, taxonomy) {
        var depth = 0;
        var current = taxonomy && taxonomy[tag] ? taxonomy[tag].parent : '';
        while (current) {
            depth += 1;
            current = taxonomy && taxonomy[current] ? taxonomy[current].parent : '';
        }
        return depth;
    }

    function isAncestorTag(maybeAncestor, tag, taxonomy) {
        var current = taxonomy && taxonomy[tag] ? taxonomy[tag].parent : '';
        while (current) {
            if (current === maybeAncestor) {
                return true;
            }
            current = taxonomy && taxonomy[current] ? taxonomy[current].parent : '';
        }
        return false;
    }

    function buildConceptMapData(narrations, taxonomy, nodeLimit, edgeThreshold) {
        var safeNarrations = Array.isArray(narrations) ? narrations : [];
        var limit = clampPositiveInt(nodeLimit, DEFAULT_NODE_LIMIT);
        var threshold = clampPositiveInt(edgeThreshold, DEFAULT_EDGE_THRESHOLD);
        var counts = {};
        var categories = {};
        var parents = {};
        var depths = {};
        var hierarchyEdges = {};

        safeNarrations.forEach(function(item) {
            var tags = expandedTagSet(item && item.topic_tags, taxonomy);
            tags.forEach(function(tag) {
                if (!tag) {
                    return;
                }
                counts[tag] = (counts[tag] || 0) + 1;
                if (!categories[tag] && taxonomy && taxonomy[tag] && taxonomy[tag].category) {
                    categories[tag] = taxonomy[tag].category;
                }
                if (depths[tag] == null) {
                    depths[tag] = depthFor(tag, taxonomy);
                }
                if (parents[tag] == null) {
                    parents[tag] = taxonomy && taxonomy[tag] && taxonomy[tag].parent
                        ? taxonomy[tag].parent
                        : '';
                }
            });
            uniqueTags(item && item.topic_tags).forEach(function(tag) {
                var parent = taxonomy && taxonomy[tag] && taxonomy[tag].parent
                    ? taxonomy[tag].parent
                    : '';
                if (!parent) {
                    return;
                }
                var hierarchyKey = [parent, tag].join('||');
                hierarchyEdges[hierarchyKey] = (hierarchyEdges[hierarchyKey] || 0) + 1;
            });
        });

        var sortedNodes = Object.keys(counts).map(function(tag) {
            return {
                id: tag,
                count: counts[tag],
                category: categories[tag] || 'other',
                parent: parents[tag] || '',
                depth: depths[tag] || 0
            };
        }).sort(function(left, right) {
            if (left.depth !== right.depth) {
                return left.depth - right.depth;
            }
            if (right.count !== left.count) {
                return right.count - left.count;
            }
            return left.id.localeCompare(right.id);
        });

        var pruned = sortedNodes.length > limit;
        var nodes = sortedNodes.slice(0, limit);
        var allowed = {};
        nodes.forEach(function(node) {
            allowed[node.id] = true;
        });

        var coOccurrence = {};
        safeNarrations.forEach(function(item) {
            var tags = expandedTagSet(item && item.topic_tags, taxonomy).filter(function(tag, index, items) {
                return !!allowed[tag] && items.indexOf(tag) === index;
            });
            tags = tags.slice().sort();
            for (var i = 0; i < tags.length; i++) {
                for (var j = i + 1; j < tags.length; j++) {
                    if (isAncestorTag(tags[i], tags[j], taxonomy) || isAncestorTag(tags[j], tags[i], taxonomy)) {
                        continue;
                    }
                    var key = tags[i] + '||' + tags[j];
                    coOccurrence[key] = (coOccurrence[key] || 0) + 1;
                }
            }
        });

        var edges = Object.keys(hierarchyEdges).map(function(key) {
            var parts = key.split('||');
            return {
                source: parts[0],
                target: parts[1],
                weight: hierarchyEdges[key],
                type: 'hierarchy'
            };
        }).filter(function(edge) {
            return !!allowed[edge.source] && !!allowed[edge.target];
        }).concat(Object.keys(coOccurrence).map(function(key) {
            var parts = key.split('||');
            return {
                source: parts[0],
                target: parts[1],
                weight: coOccurrence[key],
                type: 'cooccurrence'
            };
        }).filter(function(edge) {
            return edge.weight >= threshold;
        })).sort(function(left, right) {
            if (left.type !== right.type) {
                return left.type === 'hierarchy' ? -1 : 1;
            }
            return right.weight - left.weight;
        });

        return {
            nodes: nodes,
            edges: edges,
            graphNodesPruned: pruned
        };
    }

    function nextVisibleNarrationCount(currentCount, totalCount, increment) {
        var current = Math.max(0, Number(currentCount) || 0);
        var total = Math.max(0, Number(totalCount) || 0);
        var step = clampPositiveInt(increment, 12);
        return Math.min(total, current + step);
    }

    return {
        DEFAULT_NODE_LIMIT: DEFAULT_NODE_LIMIT,
        DEFAULT_EDGE_THRESHOLD: DEFAULT_EDGE_THRESHOLD,
        buildConceptMapData: buildConceptMapData,
        nextVisibleNarrationCount: nextVisibleNarrationCount
    };
}));
