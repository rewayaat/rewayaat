#!/usr/bin/env ruby
# frozen_string_literal: true

require 'json'
require 'optparse'
require 'open3'
require 'cgi'

options = {
  batches_dir: 'batches',
  base_url: 'http://localhost:8002',
  count: 20,
  seed: 1234,
  books: []
}

OptionParser.new do |opts|
  opts.banner = 'Usage: sample_chain_extraction.rb [options]'

  opts.on('--book BOOK', 'Book name to sample. Repeatable.') do |book|
    options[:books] << book
  end

  opts.on('--count N', Integer, 'Samples per book (default: 20)') do |count|
    options[:count] = count
  end

  opts.on('--seed N', Integer, 'Random seed (default: 1234)') do |seed|
    options[:seed] = seed
  end

  opts.on('--base-url URL', 'Application base URL (default: http://localhost:8002)') do |base_url|
    options[:base_url] = base_url
  end

  opts.on('--batches-dir DIR', 'Batch directory (default: batches)') do |dir|
    options[:batches_dir] = dir
  end
end.parse!

def truncated(value, limit = 220)
  normalized = value.to_s.gsub(/\s+/, ' ').strip
  return normalized if normalized.length <= limit

  normalized[0, limit - 3] + '...'
end

def curl_json(base_url, hadith_id)
  stdout, stderr, status = Open3.capture3(
    'curl',
    '-s',
    '-G',
    '--data-urlencode',
    %(q=id:"#{hadith_id}"),
    "#{base_url}/v1/narrations"
  )
  unless status.success?
    raise "curl failed for #{hadith_id}: exit=#{status.exitstatus} stderr=#{stderr.inspect} stdout=#{stdout[0, 240].inspect}"
  end

  JSON.parse(stdout)
end

def load_books(batches_dir)
  books = Hash.new { |h, k| h[k] = [] }
  Dir.glob(File.join(batches_dir, '*.jsonl')).sort.each do |path|
    File.foreach(path) do |line|
      next if line.strip.empty?

      entry = JSON.parse(line)
      id = entry['_id']
      source = entry['_source'] || {}
      book = source['book']
      next if id.to_s.empty? || book.to_s.empty?

      books[book] << {
        'id' => id,
        'book' => book,
        'number' => source['number'],
        'chapter' => source['chapter'],
        'english' => source['english'],
        'arabic' => source['arabic']
      }
    end
  end
  books
end

books = load_books(options[:batches_dir])

selected_books =
  if options[:books].empty?
    books.keys.sort
  else
    options[:books]
  end

rng = Random.new(options[:seed])

selected_books.each do |book|
  entries = books[book]
  abort("Unknown book: #{book}") if entries.nil?

  sample_size = [options[:count], entries.length].min
  samples = entries.shuffle(random: rng).first(sample_size)
  puts "#{'=' * 30}"
  puts "BOOK: #{book}"
  puts "SAMPLES: #{samples.length}"
  puts "#{'=' * 30}"
  puts

  samples.each_with_index do |entry, index|
    response = curl_json(options[:base_url], entry['id'])
    result = (response['collection'] || []).find { |item| item['_id'] == entry['id'] } || {}
    english_chain = result['englishChain']
    english_content = result['englishContent'] || result['english']
    arabic_chain = result['arabicChain']
    arabic_content = result['arabicContent'] || result['arabic']

    puts "[#{index + 1}] #{entry['id']}"
    puts "chapter: #{entry['chapter']}"
    puts "raw english: #{truncated(entry['english'])}"
    puts "english chain: #{truncated(english_chain)}"
    puts "english content: #{truncated(english_content)}"
    puts "raw arabic: #{truncated(entry['arabic'])}"
    puts "arabic chain: #{truncated(arabic_chain)}"
    puts "arabic content: #{truncated(arabic_content)}"
    puts
  end
end
