new Vue({
    el: '.edit-page',
    data: {
        hadithId: hadithId,
        loading: true,
        saving: false,
        error: null,
        saveSuccess: false,
        saveError: false,
        narration: null,
        taxonomy: {},
        form: {
            book: '',
            volume: '',
            part: '',
            section: '',
            chapter: '',
            number: '',
            english: '',
            arabic: '',
            notes: '',
            source: '',
            publisher: '',
            edition: '',
            topic_tags: []
        },
        tagInput: '',
        allTopicTags: []
    },
    computed: {
        filteredTopicSuggestions: function() {
            var self = this;
            if (!this.tagInput || this.tagInput.length < 1) {
                return [];
            }
            var input = this.tagInput.toLowerCase();
            return this.allTopicTags.filter(function(tag) {
                var label = (tag.en || tag.slug || '').toLowerCase();
                var alreadySelected = self.form.topic_tags.indexOf(tag.slug) >= 0;
                return !alreadySelected && label.indexOf(input) >= 0;
            }).slice(0, 10);
        }
    },
    methods: {
        loadHadith: function() {
            var self = this;
            if (!this.hadithId) {
                this.error = 'No hadith ID provided';
                this.loading = false;
                return;
            }
            axios.get('/v1/narrations/' + encodeURIComponent(this.hadithId))
                .then(function(response) {
                    if (!response.data || !response.data.ok || !response.data.narration) {
                        throw new Error('Failed to load hadith');
                    }
                    self.narration = response.data.narration;
                    self.populateForm(response.data.narration);
                })
                .then(function() {
                    return self.loadTaxonomy();
                })
                .then(function() {
                    self.loading = false;
                })
                .catch(function(err) {
                    self.error = err.message || 'Failed to load hadith';
                    self.loading = false;
                });
        },
        loadTaxonomy: function() {
            var self = this;
            return axios.get('/taxonomy.json?v=' + Date.now())
                .then(function(response) {
                    var taxonomy = response.data || [];
                    self.taxonomy = {};
                    self.allTopicTags = [];
                    taxonomy.forEach(function(entry) {
                        if (entry && entry.slug) {
                            self.taxonomy[entry.slug] = entry;
                            self.allTopicTags.push(entry);
                        }
                    });
                })
                .catch(function() {
                    // Taxonomy load failed, continue without it
                });
        },
        populateForm: function(narration) {
            this.form = {
                book: narration.book || '',
                volume: narration.volume || '',
                part: narration.part || '',
                section: narration.section || '',
                chapter: narration.chapter || '',
                number: narration.number || '',
                english: narration.english || '',
                arabic: narration.arabic || '',
                notes: narration.notes || '',
                source: narration.source || '',
                publisher: narration.publisher || '',
                edition: narration.edition || '',
                topic_tags: narration.topic_tags || []
            };
        },
        taxonomyLabel: function(slug) {
            var entry = this.taxonomy[slug];
            return (entry && entry.en) || slug;
        },
        focusTagInput: function() {
            if (this.$refs.tagInputRef) {
                this.$refs.tagInputRef.focus();
            }
        },
        addTopicTag: function(slug) {
            if (this.form.topic_tags.indexOf(slug) < 0) {
                this.form.topic_tags.push(slug);
            }
            this.tagInput = '';
            this.focusTagInput();
        },
        removeTopicTag: function(index) {
            this.form.topic_tags.splice(index, 1);
        },
        handleTagKeydown: function(e) {
            if (e.key === 'Enter' || e.key === ',') {
                e.preventDefault();
                var suggestions = this.filteredTopicSuggestions;
                if (suggestions.length === 1) {
                    this.addTopicTag(suggestions[0].slug);
                } else if (this.tagInput.trim()) {
                    // Try to add as-is if it matches an existing tag
                    var input = this.tagInput.trim().toLowerCase();
                    var match = this.allTopicTags.find(function(tag) {
                        return tag.slug === input ||
                            (tag.en || '').toLowerCase() === input;
                    });
                    if (match) {
                        this.addTopicTag(match.slug);
                    }
                    this.tagInput = '';
                }
            } else if (e.key === 'Backspace' && !this.tagInput && this.form.topic_tags.length > 0) {
                this.removeTopicTag(this.form.topic_tags.length - 1);
            }
        },
        saveHadith: function() {
            var self = this;
            this.saving = true;
            this.saveSuccess = false;
            this.saveError = false;

            var payload = {
                book: this.form.book || null,
                volume: this.form.volume || null,
                part: this.form.part || null,
                section: this.form.section || null,
                chapter: this.form.chapter || null,
                number: this.form.number || null,
                english: this.form.english || null,
                arabic: this.form.arabic || null,
                notes: this.form.notes || null,
                source: this.form.source || null,
                publisher: this.form.publisher || null,
                edition: this.form.edition || null,
                topic_tags: this.form.topic_tags.length > 0 ? this.form.topic_tags : null
            };

            axios.put('/v1/narrations/' + encodeURIComponent(this.hadithId), payload)
                .then(function(response) {
                    if (!response.data || !response.data.ok) {
                        throw new Error((response.data && response.data.message) || 'Failed to save');
                    }
                    self.saveSuccess = true;
                    self.narration = response.data.narration;
                    setTimeout(function() {
                        self.saveSuccess = false;
                    }, 3000);
                })
                .catch(function(err) {
                    self.saveError = true;
                    self.error = err.response?.data?.message || err.message || 'Failed to save';
                    setTimeout(function() {
                        self.saveError = false;
                    }, 3000);
                })
                .finally(function() {
                    self.saving = false;
                });
        }
    },
    mounted: function() {
        this.loadHadith();
    }
});
