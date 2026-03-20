
    Vue.component(
            'hadith-details', {
                template: `
                    <div>
                       <div class="meta-item" v-if="narration.book">
                          <div class="meta-icon"><i class="fa fa-book" aria-hidden="true"></i></div>
                          <div>
                             <div class="meta-label">Book</div>
                             <div class="meta-text link" v-html="narration.book" v-on:click="showBookBlurb(narration.book)"></div>
                          </div>
                       </div>
                       <div class="meta-item" v-if="narration.edition">
                          <div class="meta-icon"><i class="fa fa-pencil-square-o" aria-hidden="true"></i></div>
                          <div>
                             <div class="meta-label">Edition</div>
                             <div class="meta-text">({{narration.edition}})</div>
                          </div>
                       </div>
                       <div class="meta-item" v-if="narration.number">
                          <div class="meta-icon"><i class="fa fa-pencil-square-o" aria-hidden="true"></i></div>
                          <div>
                             <div class="meta-label">Number</div>
                             <div class="meta-text">Hadith #{{narration.number}}</div>
                          </div>
                       </div>
                       <div class="meta-item" v-if="narration.chapter">
                          <div class="meta-icon"><i class="fa fa-superpowers" aria-hidden="true"></i></div>
                          <div>
                             <div class="meta-label">Chapter</div>
                             <div class="meta-text link" v-html="narration.chapter" v-on:click="showSpecific(narration, 'chapter')"></div>
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
                       <div class="meta-item" v-if="narration.volume">
                          <div class="meta-icon"><i class="fa fa-calendar-o" aria-hidden="true"></i></div>
                          <div>
                             <div class="meta-label">Volume</div>
                             <div class="meta-text link" v-html="narration.volume" v-on:click="showSpecific(narration, 'volume')"></div>
                          </div>
                       </div>
                       <div class="meta-item" v-if="narration.source">
                          <div class="meta-icon"><i class="fa fa-share-square-o" aria-hidden="true"></i></div>
                          <div>
                             <div class="meta-label">Source</div>
                             <div class="meta-text" v-html="narration.source"></div>
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
                props: ['narration'],
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
                            selections.volume = strip(String(narration.volume || '')).replace("Volume", "").trim();
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
                        redirectToSearchResult(query, 1, sortFields, 'read', '', searchMatchMode, 'browse');
                    }
                }
            });

    // create pagination component
    Vue.component('pagination', {
        template: `
            <ul v-if="showList()" class="pagination pagination-sm flex-wrap mb-0 gap-1 justify-content-end">
               <li class="page-item" v-bind:class="{disabled: !showPrevious()}" v-on:click="goToPrevious()">
                   <a class="page-link" href="javascript:void(0)">
                       <i class="fa fa-chevron-left" aria-hidden="true"></i>
                       Previous
                   </a>
               </li>
               <li class="page-item" v-bind:class="{active: isActivePage(n)}" v-if="showPage(n)" v-for="n in 20">
                   <a class="page-link" href="javascript:void(0)" v-on:click="goToPage(n)">{{n}}</a>
               </li>
               <li class="page-item" v-bind:class="{disabled: !showNext()}" v-on:click="goToNext()">
                   <a class="page-link" href="javascript:void(0)">
                       Next
                       <i class="fa fa-chevron-right" aria-hidden="true"></i>
                   </a>
               </li>
            </ul>
        `,
        methods: {
            isActivePage: function(n) {
                return n == (this.$root.page);
            },
            showList: function() {
                if (this.$root.totalHits > this.$root.pageSize) {
                    return true;
                } else {
                    return false;
                }
            },
            showPage: function(n) {
                if (this.$root.totalPages >= n) {
                    return true;
                } else {
                    return false;
                }
            },
            showPrevious: function() {
                if (this.$root.page > 1) {
                    return true;
                } else {
                    return false;
                }
            },
            showNext: function() {
                if (this.$root.page < 20 && this.$root.totalPages > this.$root.page) {
                    return true;
                } else {
                    return false;
                }
            },
            goToPrevious: function() {
                if (this.showPrevious()) {
                    this.goToPage(this.$root.page - 1);
                }
            },
            goToNext: function() {
                if (this.showNext()) {
                    this.goToPage(this.$root.page + 1);
                }
            },
            goToPage: function(n) {
                if (n !== this.$root.page) {
                    if (this.$root.collectionMode && this.$root.collectionId) {
                        openCollectionPage(this.$root.collectionId, n, this.$root.activeTopicTags || []);
                        return;
                    }
                    redirectToSearchResult(getQueryStringValue('q'), n, getQueryStringValue
                    ('sort_fields'));
                }
            }
        }
    });
