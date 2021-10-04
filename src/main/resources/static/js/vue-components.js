
    Vue.component(
            'hadith-details', {
                template: `
                    <div>
                       <div v-on:click="showBookBlurb(narration.book)" title="Book" uk-tooltip="pos: right"  class="uk-align-left" >
                          <i class="fa fa-book hadithDetailsIcon"
                             aria-hidden="true"></i>
                          <p class="hadithDetailsTitle hadithDetailsLink" v-html="narration.book" />
                       </div>
                       <div title="Edition" uk-tooltip="pos: right"  class="uk-align-left"  v-if="narration.edition">
                          <i class="fa fa-pencil-square-o hadithDetailsIcon"
                             aria-hidden="true"></i>
                          <p class="hadithDetailsTitle">({{narration.edition}})</p>
                       </div>
                       <div title="Number" uk-tooltip="pos: right"  class="uk-align-left"  v-if="narration.number">
                          <i class="fa fa-pencil-square-o hadithDetailsIcon"
                             aria-hidden="true"></i>
                          <p class="hadithDetailsTitle">Hadith #{{narration.number}}</p>
                       </div>
                       <div v-on:click="showSpecific(narration.chapter, '', '', '', narration.book)"
                        title="Chapter"
                       uk-tooltip="pos: right"  class="uk-align-left"  v-if="narration.chapter">
                          <i class="fa fa-superpowers hadithDetailsIcon" aria-hidden="true"></i>
                          <p class="hadithDetailsTitle hadithDetailsLink" v-html="narration.chapter" />
                       </div>
                       <div title="Section"
                       v-on:click="showSpecific('', narration.section, '', '', narration.book)"
                       uk-tooltip="pos: right"  class="uk-align-left" v-if="narration.section">
                          <i class="fa fa-bookmark-o hadithDetailsIcon" aria-hidden="true"></i>
                          <p class="hadithDetailsTitle hadithDetailsLink" v-html="narration.section" />
                       </div>
                       <div title="Part"
                          v-on:click="showSpecific('', '', narration.part, '', narration.book)"
                          uk-tooltip="pos: right"   class="uk-align-left"  v-if="narration.part">
                          <i class="fa fa-clone hadithDetailsIcon" aria-hidden="true"></i>
                          <p class="hadithDetailsTitle hadithDetailsLink" v-html="narration.part" />
                       </div>
                       <div title="Volume"
                          v-on:click="showSpecific('', '', '', narration.volume, narration.book)"
                          uk-tooltip="pos: right"   class="uk-align-left"  v-if="narration.volume">
                          <i class="fa fa-calendar-o hadithDetailsIcon" aria-hidden="true"></i>
                          <p class="hadithDetailsTitle hadithDetailsLink" v-html="narration.volume" />
                       </div>
                       <div title="Source" uk-tooltip="pos: right"  class="uk-align-left"  v-if="narration.source">
                          <i class="fa fa-share-square-o hadithDetailsIcon" aria-hidden="true"></i>
                          <p class="hadithDetailsTitle" v-html="narration.source" />
                       </div>
                       <div title="Publisher" uk-tooltip="pos: right" class="uk-align-left"  v-if="narration.publisher">
                          <i class="fa fa-medium hadithDetailsIcon" aria-hidden="true"></i>
                          <p class="hadithDetailsTitle" v-html="narration.publisher" />
                       </div>
                    </div>
                `,
                props: ['narration'],
                methods: {
                    showBookBlurb: function(bookName) {
                        showBookBlurb(bookName)
                    },
                    showSpecific: function(chapter="", section="", part="", volume="", book="") {
                        var query = "book:\"" + strip(book) + "\"";
                        var sortFields = "number:asc";
                        if (chapter) {
                            query += " chapter:\"" + strip(chapter) + "\"";
                            sortFields = "chapter:asc," + sortFields;
                        }
                        if (section) {
                            query += " section:\"" + strip(section) + "\"";
                            sortFields = "section:asc," + sortFields;
                        }
                        if (part) {
                            query += " part:\"" + strip(part) + "\"";
                            sortFields = "part:asc," + sortFields;
                        }
                        if (volume) {
                            query += " volume:\"" + strip(volume.replace("Volume", "")) + "\"";
                            sortFields = "volume:asc," + sortFields;
                        }
                        redirectToSearchResult(query, '', sortFields);
                    }
                }
            });

    // create pagination component
    Vue.component('pagination', {
        template: `
            <ul v-if="showList()" style="margin-top: 25px;margin-bottom: -35px; margin-left: -40px;
            font-size: 15px;" class="uk-pagination uk-flex-left">
               <li v-if="showPrevious()" v-on:click="goToPrevious()" style="margin-right:15px;"><a><span>&lArr; Previous</span></a></li>
               <li v-bind:class="isActivePage(n)" v-if="showPage(n)" v-for="n in 20"><a v-on:click="goToPage(n)">{{n}}</a></li>
               <li v-if="showNext()" v-on:click="goToNext()" style="margin-left:15px;"><a><span>Next &rArr;</span></a></li>
            </ul>
        `,
        methods: {
            isActivePage: function(n) {
                if (n == (this.$root.page)) {
                    return 'uk-active';
                }
            },
            showList: function() {
                if (this.$root.totalHits > this.$root.pageSize) {
                    return true;
                } else {
                    return false;
                }
            },
            showPage: function(n) {
                if (Math.ceil(this.$root.totalHits / this.$root.pageSize) >= n) {
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
                if (this.$root.page < 21 && ((this.$root.totalHits / this.$root.pageSize) > (this.$root.page))) {
                    return true;
                } else {
                    return false;
                }
            },
            goToPrevious: function() {
                this.goToPage(this.$root.page - 1);
            },
            goToNext: function() {
                this.goToPage(this.$root.page + 1);
            },
            goToPage: function(n) {
                if (n !== this.$root.page) {
                    redirectToSearchResult(getQueryStringValue('q'), n, getQueryStringValue
                    ('sort_fields'));
                }
            }
        }
    });
