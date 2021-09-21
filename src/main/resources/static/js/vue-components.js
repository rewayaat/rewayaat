
    Vue.component(
            'hadith-details', {
                template: `
                    <div>
                       <div v-on:click="showBookBlurb(narration.book)" title="Book" uk-tooltip="pos: right"  class="uk-align-left" >
                          <i class="fa fa-book hadithDetailsIcon"
                             aria-hidden="true"></i>
                          <p style="text-decoration:underline; cursor: pointer;" class="hadithDetailsTitle" v-html="narration.book" />
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
                       <div title="Chapter" uk-tooltip="pos: right"  class="uk-align-left"  v-if="narration.chapter">
                          <i class="fa fa-superpowers hadithDetailsIcon" aria-hidden="true"></i>
                          <p class="hadithDetailsTitle" v-html="narration.chapter" />
                       </div>
                       <div title="Section" uk-tooltip="pos: right"  class="uk-align-left" v-if="narration.section">
                          <i class="fa fa-bookmark-o hadithDetailsIcon" aria-hidden="true"></i>
                          <p class="hadithDetailsTitle" v-html="narration.section" />
                       </div>
                       <div title="Part" uk-tooltip="pos: right"   class="uk-align-left"  v-if="narration.part">
                          <i class="fa fa-clone hadithDetailsIcon" aria-hidden="true"></i>
                          <p class="hadithDetailsTitle" v-html="narration.part" />
                       </div>
                       <div title="Volume" uk-tooltip="pos: right"   class="uk-align-left"  v-if="narration.volume">
                          <i class="fa fa-calendar-o hadithDetailsIcon" aria-hidden="true"></i>
                          <p class="hadithDetailsTitle" v-html="narration.volume" />
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
                    window.location.href = window.location.protocol + "//" +
                        window.location.host + window.location.pathname + '?' + 'q=' +
                        encodeURIComponent(getQueryStringValue('q')) + '&page=' + n;
                }
            }
        }
    });
