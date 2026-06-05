
    Vue.component(
            'pagination', {
                template: `
                    <nav v-if="showList" aria-label="Page navigation">
                        <ul class="pagination pagination-sm justify-content-center">
                            <li class="page-item pagination__first" :class="{disabled: currentPage <= 1}">
                                <a class="page-link" href="#" @click.prevent="goToPage(1)" aria-label="First">
                                    <span aria-hidden="true">&laquo;</span>
                                </a>
                            </li>
                            <li class="page-item" :class="{disabled: currentPage <= 1}">
                                <a class="page-link" href="#" @click.prevent="goToPage(currentPage - 1)" aria-label="Previous">
                                    <span aria-hidden="true">&lsaquo;</span>
                                </a>
                            </li>
                            <li v-for="page in visiblePages" :key="page" class="page-item pagination__number" :class="{active: page === currentPage}">
                                <a v-if="page !== '...'" class="page-link" href="#" @click.prevent="goToPage(page)">{{ page }}</a>
                                <span v-else class="page-link">...</span>
                            </li>
                            <li class="page-item pagination__mobile-label">
                                <span class="page-link">{{ currentPage }} / {{ totalPages }}</span>
                            </li>
                            <li class="page-item" :class="{disabled: currentPage >= totalPages}">
                                <a class="page-link" href="#" @click.prevent="goToPage(currentPage + 1)" aria-label="Next">
                                    <span aria-hidden="true">&rsaquo;</span>
                                </a>
                            </li>
                            <li class="page-item pagination__last" :class="{disabled: currentPage >= totalPages}">
                                <a class="page-link" href="#" @click.prevent="goToPage(totalPages)" aria-label="Last">
                                    <span aria-hidden="true">&raquo;</span>
                                </a>
                            </li>
                        </ul>
                    </nav>
                `,
                computed: {
                    currentPage: function() {
                        return this.$root.page || 1;
                    },
                    totalPages: function() {
                        return this.$root.totalPages || 1;
                    },
                    showList: function() {
                        // Only show pagination if there are multiple pages
                        return this.totalPages > 1;
                    },
                    visiblePages: function() {
                        var pages = [];
                        var current = this.currentPage;
                        var total = this.totalPages;
                        var delta = 2; // Number of pages to show on each side

                        // Always show first page
                        if (current > delta + 2) {
                            pages.push(1);
                            if (current > delta + 3) {
                                pages.push('...');
                            }
                        }

                        // Show pages around current
                        var start = Math.max(2, current - delta);
                        var end = Math.min(total - 1, current + delta);

                        for (var i = start; i <= end; i++) {
                            pages.push(i);
                        }

                        // Always show last page
                        if (current < total - delta - 1) {
                            if (current < total - delta - 2) {
                                pages.push('...');
                            }
                            pages.push(total);
                        } else if (total > 1) {
                            pages.push(total);
                        }

                        return pages;
                    }
                },
                methods: {
                    goToPage: function(pageNum) {
                        if (pageNum < 1 || pageNum > this.totalPages || pageNum === this.currentPage) {
                            return;
                        }
                        // Use URL-based navigation for reading/collection mode
                        var query = this.$root.queryStr || '';
                        var sortFields = this.$root.sortFields || '';
                        var mode = this.$root.readingMode ? 'read' : null;
                        redirectToSearchResult(query, pageNum, sortFields, mode);
                    }
                }
            }
    );

    Vue.component(
            'hadith-details', {
                template: `
                    <div class="hadith-details">
                       <div class="meta-item"
                            v-for="item in visibleMetadataItems"
                            v-bind:key="item.key">
                          <div class="meta-icon"><i v-bind:class="item.icon" aria-hidden="true"></i></div>
                          <div>
                             <div class="meta-label">{{ item.label }}</div>
                             <div class="meta-text"
                                  v-bind:class="{link: item.clickable}"
                                  v-html="item.html"
                                  v-on:click="handleMetadataClick(item)"></div>
                          </div>
                       </div>
                       <button type="button"
                               class="hadith-details__mobile-toggle"
                               v-if="hasCollapsedMobileMetadata"
                               v-bind:aria-expanded="mobileExpanded ? 'true' : 'false'"
                               v-on:click="mobileExpanded = !mobileExpanded">
                          <span>{{ mobileExpanded ? 'Show fewer details' : 'Show all details' }}</span>
                          <i class="fa"
                             v-bind:class="mobileExpanded ? 'fa-angle-up' : 'fa-angle-down'"
                             aria-hidden="true"></i>
                       </button>
                    </div>
                `,
                props: {
                    narration: {
                        type: Object,
                        required: true
                    },
                    hadithNumber: {
                        type: [Number, String],
                        default: null
                    }
                },
                data: function() {
                    return {
                        mobileExpanded: false,
                        mobileViewport: typeof window !== 'undefined' ? window.innerWidth <= 768 : false
                    };
                },
                computed: {
                    metadataItems: function() {
                        var items = [];
                        if (this.hadithNumber !== null && this.hadithNumber !== undefined && this.hadithNumber !== '') {
                            items.push({
                                key: 'hadith-number',
                                icon: 'fa fa-hashtag',
                                label: 'Hadith #',
                                html: this.escapeHtml(this.hadithNumber),
                                clickable: false
                            });
                        }
                        if (this.isRealValue(this.narration.book)) {
                            items.push({
                                key: 'book',
                                icon: 'fa fa-book',
                                label: 'Book',
                                html: this.narration.book,
                                clickable: true,
                                onClick: this.showSpecific.bind(this, this.narration, 'book')
                            });
                        }
                        if (this.isRealValue(this.narration.volume)) {
                            items.push({
                                key: 'volume',
                                icon: 'fa fa-layer-group',
                                label: 'Volume',
                                html: this.narration.volume,
                                clickable: true,
                                onClick: this.showSpecific.bind(this, this.narration, 'volume')
                            });
                        }
                        if (this.isRealValue(this.narration.section)) {
                            items.push({
                                key: 'section',
                                icon: 'fa fa-bookmark',
                                label: 'Section',
                                html: this.narration.section,
                                clickable: true,
                                onClick: this.showSpecific.bind(this, this.narration, 'section')
                            });
                        }
                        if (this.isRealValue(this.narration.part)) {
                            items.push({
                                key: 'part',
                                icon: 'fa fa-clone',
                                label: 'Part',
                                html: this.narration.part,
                                clickable: true,
                                onClick: this.showSpecific.bind(this, this.narration, 'part')
                            });
                        }
                        if (this.isRealValue(this.narration.chapter)) {
                            items.push({
                                key: 'chapter',
                                icon: 'fa fa-heading',
                                label: 'Chapter',
                                html: this.narration.chapter,
                                clickable: true,
                                onClick: this.showSpecific.bind(this, this.narration, 'chapter')
                            });
                        }
                        if (this.isRealValue(this.narration.source)) {
                            items.push({
                                key: 'source',
                                icon: 'fa fa-arrow-right-from-bracket',
                                label: 'Source',
                                html: this.narration.source,
                                clickable: false
                            });
                        }
                        if (this.isRealValue(this.narration.edition)) {
                            items.push({
                                key: 'edition',
                                icon: 'fa fa-pen-to-square',
                                label: 'Edition',
                                html: this.escapeHtml('(' + this.narration.edition + ')'),
                                clickable: false
                            });
                        }
                        if (this.isRealValue(this.narration.publisher)) {
                            items.push({
                                key: 'publisher',
                                icon: 'fa fa-building',
                                label: 'Publisher',
                                html: this.narration.publisher,
                                clickable: false
                            });
                        }
                        return items;
                    },
                    visibleMetadataItems: function() {
                        if (!this.mobileViewport || this.mobileExpanded || this.metadataItems.length <= 2) {
                            return this.metadataItems;
                        }
                        return this.metadataItems.slice(0, 2);
                    },
                    hasCollapsedMobileMetadata: function() {
                        return this.mobileViewport && this.metadataItems.length > 2;
                    }
                },
                mounted: function() {
                    if (typeof window !== 'undefined') {
                        this._handleMobileViewportResize = this.handleViewportResize.bind(this);
                        window.addEventListener('resize', this._handleMobileViewportResize);
                        this.handleViewportResize();
                    }
                },
                beforeDestroy: function() {
                    if (typeof window !== 'undefined' && this._handleMobileViewportResize) {
                        window.removeEventListener('resize', this._handleMobileViewportResize);
                    }
                },
                methods: {
                    isRealValue: function(val) {
                        if (!val) return false;
                        var s = String(val).replace(/<[^>]*>/g, '').trim().toLowerCase();
                        return s !== '' && s !== 'content';
                    },
                    escapeHtml: function(value) {
                        var div = document.createElement('div');
                        div.textContent = value == null ? '' : String(value);
                        return div.innerHTML;
                    },
                    handleViewportResize: function() {
                        this.mobileViewport = window.innerWidth <= 768;
                        if (!this.mobileViewport) {
                            this.mobileExpanded = false;
                        }
                    },
                    handleMetadataClick: function(item) {
                        if (item && item.clickable && typeof item.onClick === 'function') {
                            item.onClick();
                        }
                    },
                    showBookBlurb: function(bookName) {
                        showBookBlurb(bookName)
                    },
                    showSpecific: function(narration, targetLevel) {
                        if (!narration || !narration.book) {
                            return;
                        }
                        var selections = {
                            book: strip(narration.book).trim(),
                            volume: '',
                            part: '',
                            section: '',
                            chapter: ''
                        };
                        if (targetLevel === 'volume' || targetLevel === 'part' || targetLevel === 'section' || targetLevel === 'chapter') {
                            selections.volume = strip(String(narration.volume || '')).replace(/^Volume\s+/i, '').trim();
                        }
                        if (targetLevel === 'part' || targetLevel === 'section' || targetLevel === 'chapter') {
                            selections.part = strip(narration.part || '').trim();
                        }
                        if (targetLevel === 'section') {
                            selections.section = strip(narration.section || '').trim();
                        }
                        if (targetLevel === 'chapter') {
                            selections.chapter = strip(narration.chapter || '').trim();
                        }
                        var query = buildQueryFromFilters(selections);
                        var sortFields = buildSortFields(selections);
                        redirectToSearchResult(query, 1, sortFields);
                    }
                }
            }
    );
