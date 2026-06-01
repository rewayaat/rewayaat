
    Vue.component(
            'pagination', {
                template: `
                    <nav v-if="showList" aria-label="Page navigation">
                        <ul class="pagination pagination-sm justify-content-center">
                            <li class="page-item" :class="{disabled: currentPage <= 1}">
                                <a class="page-link" href="#" @click.prevent="goToPage(1)" aria-label="First">
                                    <span aria-hidden="true">&laquo;</span>
                                </a>
                            </li>
                            <li class="page-item" :class="{disabled: currentPage <= 1}">
                                <a class="page-link" href="#" @click.prevent="goToPage(currentPage - 1)" aria-label="Previous">
                                    <span aria-hidden="true">&lsaquo;</span>
                                </a>
                            </li>
                            <li v-for="page in visiblePages" :key="page" class="page-item" :class="{active: page === currentPage}">
                                <a v-if="page !== '...'" class="page-link" href="#" @click.prevent="goToPage(page)">{{ page }}</a>
                                <span v-else class="page-link">...</span>
                            </li>
                            <li class="page-item" :class="{disabled: currentPage >= totalPages}">
                                <a class="page-link" href="#" @click.prevent="goToPage(currentPage + 1)" aria-label="Next">
                                    <span aria-hidden="true">&rsaquo;</span>
                                </a>
                            </li>
                            <li class="page-item" :class="{disabled: currentPage >= totalPages}">
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
                       <div class="meta-item" v-if="hadithNumber !== null && hadithNumber !== undefined && hadithNumber !== ''">
                          <div class="meta-icon"><i class="fa fa-pencil-square-o" aria-hidden="true"></i></div>
                          <div>
                             <div class="meta-label">Hadith #</div>
                             <div class="meta-text">{{hadithNumber}}</div>
                          </div>
                       </div>
                       <div class="meta-item" v-if="narration.book">
                          <div class="meta-icon"><i class="fa fa-book" aria-hidden="true"></i></div>
                          <div>
                             <div class="meta-label">Book</div>
                             <div class="meta-text link" v-html="narration.book" v-on:click="showBookBlurb(narration.book)"></div>
                          </div>
                       </div>
                       <div class="meta-item" v-if="narration.volume">
                          <div class="meta-icon"><i class="fa fa-calendar-o" aria-hidden="true"></i></div>
                          <div>
                             <div class="meta-label">Volume</div>
                             <div class="meta-text link" v-html="narration.volume" v-on:click="showSpecific(narration, 'volume')"></div>
                          </div>
                       </div>
                       <div class="meta-item" v-if="narration.section">
                          <div class="meta-icon"><i class="fa fa-bookmark-o" aria-hidden="true"></i></div>
                          <div>
                             <div class="meta-label">Section</div>
                             <div class="meta-text link" v-html="narration.section" v-on:click="showSpecific(narration, 'section')"></div>
                          </div>
                       </div>
                       <div class="meta-item" v-if="narration.part">
                          <div class="meta-icon"><i class="fa fa-clone" aria-hidden="true"></i></div>
                          <div>
                             <div class="meta-label">Part</div>
                             <div class="meta-text link" v-html="narration.part" v-on:click="showSpecific(narration, 'part')"></div>
                          </div>
                       </div>
                       <div class="meta-item" v-if="narration.chapter">
                          <div class="meta-icon"><i class="fa fa-superpowers" aria-hidden="true"></i></div>
                          <div>
                             <div class="meta-label">Chapter</div>
                             <div class="meta-text link" v-html="narration.chapter" v-on:click="showSpecific(narration, 'chapter')"></div>
                          </div>
                       </div>
                       <div class="meta-item" v-if="narration.source">
                          <div class="meta-icon"><i class="fa fa-share-square-o" aria-hidden="true"></i></div>
                          <div>
                             <div class="meta-label">Source</div>
                             <div class="meta-text" v-html="narration.source"></div>
                          </div>
                       </div>
                       <div class="meta-item" v-if="narration.edition">
                          <div class="meta-icon"><i class="fa fa-pencil-square-o" aria-hidden="true"></i></div>
                          <div>
                             <div class="meta-label">Edition</div>
                             <div class="meta-text">({{narration.edition}})</div>
                          </div>
                       </div>
                       <div class="meta-item" v-if="narration.publisher">
                          <div class="meta-icon"><i class="fa fa-medium" aria-hidden="true"></i></div>
                          <div>
                             <div class="meta-label">Publisher</div>
                             <div class="meta-text" v-html="narration.publisher"></div>
                          </div>
                       </div>
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
                methods: {
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
                        if (targetLevel === 'section' || targetLevel === 'chapter') {
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
