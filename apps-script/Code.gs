var KEY = 'SET_IN_APPS_SCRIPT'; // the real key lives only in the Apps Script project, never in this repository

var SHARED = /^(groceries|shopping)$/i; // lists shown as their own tab instead of as a person
var NOTE = /^note$/i;                   // a list whose first open task is the family note
var WEATHER = { lat: 38.44, lon: -90.20 }; // Columbia, IL 62236

// The tablet calls this address. With no action it returns the board; actions change one Google Task.
function doGet(e) {
  if (!e || !e.parameter || e.parameter.key !== KEY) return json({ error: 'wrong key' });
  var p = e.parameter;
  if (p.action === 'done') {
    Tasks.Tasks.patch({ status: 'completed' }, p.list, p.task);
    return json({ ok: true });
  }
  if (p.action === 'delete') {
    Tasks.Tasks.remove(p.list, p.task);
    return json({ ok: true });
  }
  if (p.action === 'undo') {
    var t = Tasks.Tasks.get(p.list, p.task);
    t.status = 'needsAction';
    delete t.completed;
    Tasks.Tasks.update(t, p.list, p.task);
    return json({ ok: true });
  }
  if (p.action === 'add') {
    var title = String(p.title || '').trim().substring(0, 200);
    if (!title) return json({ error: 'empty title' });
    Tasks.Tasks.insert({ title: title }, p.list);
    return json({ ok: true });
  }
  var board = readTasks();
  var cal = readCalendar();
  board.days = cal.days;
  board.calendars = cal.calendars;
  board.countdowns = cal.countdowns;
  board.dinner = cal.dinner;
  board.weather = readWeather();
  return json(board);
}

// One column per Google Tasks list name. Shows open tasks due today or earlier (or with no date),
// plus tasks completed today so finished work stays visible until midnight.
function readTasks() {
  var tz = Session.getScriptTimeZone(), now = new Date();
  var today = Utilities.formatDate(now, tz, 'yyyy-MM-dd');
  var midnight = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  var people = [], shared = [], lists = {}, tasks = [], note = '';
  (Tasks.Tasklists.list({ maxResults: 20 }).items || []).forEach(function (list) {
    var open = Tasks.Tasks.list(list.id, { maxResults: 100, showCompleted: false }).items || [];
    open.sort(function (a, b) { return a.position < b.position ? -1 : 1; });
    if (NOTE.test(list.title)) {
      for (var i = 0; i < open.length && !note; i++) if (open[i].title) note = open[i].title;
      return;
    }
    if (people.indexOf(list.title) < 0) { // two lists with one name share a column; new tasks go to the first
      people.push(list.title);
      lists[list.title] = list.id;
      if (SHARED.test(list.title)) shared.push(list.title);
    }
    var doneToday = Tasks.Tasks.list(list.id, {
      maxResults: 100, showCompleted: true, showHidden: true, completedMin: midnight.toISOString()
    }).items || [];
    open.forEach(function (t) {
      if (!t.title || t.status === 'completed') return;
      var due = t.due ? t.due.substring(0, 10) : '';
      if (due > today) return;
      tasks.push({ id: t.id, list: list.id, person: list.title, title: t.title, done: false, overdue: due !== '' && due < today });
    });
    doneToday.forEach(function (t) {
      if (!t.title || t.status !== 'completed') return;
      tasks.push({ id: t.id, list: list.id, person: list.title, title: t.title, done: true });
    });
  });
  return { people: people, shared: shared, lists: lists, tasks: tasks, note: note };
}

// Calendar: seven days starting today, countdowns to birthdays in the next 30 days, and tonight's dinner.
// Read once per calendar and kept for three minutes, because this is the slow part of every refresh.
function readCalendar() {
  var cache = CacheService.getScriptCache(), hit = cache.get('calendar');
  if (hit) return JSON.parse(hit);
  var tz = Session.getScriptTimeZone(), now = new Date(), DAY = 86400000;
  var start = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  var dayStart = function (i) { return new Date(now.getFullYear(), now.getMonth(), now.getDate() + i); };
  var cals = CalendarApp.getAllCalendars().filter(function (c) { return !c.isHidden() && c.isSelected(); });
  var buckets = [], seen = {}, countdowns = [], dinner = '';
  for (var i = 0; i < 7; i++) buckets.push([]);
  cals.forEach(function (cal) {
    var name = cal.getName(), color = cal.getColor();
    cal.getEvents(start, dayStart(30)).forEach(function (ev) {
      var allDay = ev.isAllDayEvent(), title = ev.getTitle();
      var from = allDay ? ev.getAllDayStartDate() : ev.getStartTime();
      var to = allDay ? ev.getAllDayEndDate() : ev.getEndTime(); // all-day end is the morning after the last day
      // Countdown: all-day events that look like a birthday, or whose title starts with "Countdown".
      if (allDay && from >= dayStart(1) && /birthday|^countdown/i.test(title)) {
        countdowns.push({ title: title.replace(/^countdown:?\s*/i, ''), days: Math.round((from - start) / DAY) });
      }
      for (var i = 0; i < 7; i++) {
        var a = dayStart(i), b = dayStart(i + 1);
        if (from >= b || to <= a) continue;          // not on this day
        if (!allDay && to < now) continue;           // already over
        var key = i + '|' + (allDay ? 0 : from.getTime()) + '|' + title;
        if (seen[key]) continue;
        seen[key] = true;
        if (i === 0 && /^dinner\b/i.test(title)) dinner = title.replace(/^dinner:?\s*/i, '') || title;
        buckets[i].push({
          at: allDay ? 0 : from.getTime(), time: allDay ? 'All day' : Utilities.formatDate(from, tz, 'h:mm a'),
          title: title, cal: name, color: color
        });
      }
    });
  });
  countdowns.sort(function (a, b) { return a.days - b.days; });
  var result = {
    days: buckets.map(function (found, i) {
      found.sort(function (a, b) { return a.at - b.at; });
      return {
        label: i === 0 ? 'Today' : Utilities.formatDate(dayStart(i), tz, 'EEE d'),
        events: found.map(function (f) { return { time: f.time, title: f.title, cal: f.cal, color: f.color }; })
      };
    }),
    calendars: cals.map(function (c) { return { name: c.getName(), color: c.getColor() }; }),
    countdowns: countdowns.slice(0, 3),
    dinner: dinner
  };
  cache.put('calendar', JSON.stringify(result), 180);
  return result;
}

// Today's weather from Open-Meteo (free, no account), fetched here because the tablet is too old to
// reach that service securely itself. Kept for 30 minutes. Returns null if the service can't be reached.
function readWeather() {
  var cache = CacheService.getScriptCache(), hit = cache.get('weather');
  if (hit) return JSON.parse(hit);
  try {
    var r = UrlFetchApp.fetch('https://api.open-meteo.com/v1/forecast?latitude=' + WEATHER.lat + '&longitude=' + WEATHER.lon +
      '&current_weather=true&daily=weathercode,temperature_2m_max,temperature_2m_min' +
      '&temperature_unit=fahrenheit&timezone=auto&forecast_days=1', { muteHttpExceptions: true });
    if (r.getResponseCode() !== 200) return null;
    var w = JSON.parse(r.getContentText());
    var out = {
      now: Math.round(w.current_weather.temperature),
      hi: Math.round(w.daily.temperature_2m_max[0]),
      lo: Math.round(w.daily.temperature_2m_min[0]),
      text: weatherText(w.current_weather.weathercode)
    };
    cache.put('weather', JSON.stringify(out), 1800);
    return out;
  } catch (err) {
    return null;
  }
}

// Open-Meteo uses the standard WMO weather codes.
function weatherText(code) {
  if (code === 0) return 'Clear';
  if (code <= 2) return 'Partly cloudy';
  if (code === 3) return 'Cloudy';
  if (code <= 48) return 'Fog';
  if (code <= 57) return 'Drizzle';
  if (code <= 67) return 'Rain';
  if (code <= 77) return 'Snow';
  if (code <= 82) return 'Showers';
  if (code <= 86) return 'Snow showers';
  return 'Thunderstorm';
}

function json(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

// Run from the editor to check everything: adds a temporary task, ticks, unticks and deletes it.
function selfTest() {
  var list = Tasks.Tasklists.list().items[0];
  var name = 'Board self-test ' + new Date().getTime();
  var board = function () { return JSON.parse(doGet({ parameter: { key: KEY } }).getContent()); };
  var mine = function () { return board().tasks.filter(function (x) { return x.title === name; })[0]; };
  doGet({ parameter: { key: KEY, action: 'add', list: list.id, title: name } });
  var t = mine();
  if (!t || t.done !== false || t.overdue) throw new Error('added task should show as open and not overdue');
  var act = function (a) { doGet({ parameter: { key: KEY, action: a, list: t.list, task: t.id } }); };
  try {
    act('done');
    if (!mine() || mine().done !== true) throw new Error('done did not stick');
    act('undo');
    if (!mine() || mine().done !== false) throw new Error('undo did not stick');
    act('done');
    act('delete');
    if (mine()) throw new Error('delete did not stick');
    var b = board();
    if (b.days.length !== 7 || !b.lists[b.people[0]]) throw new Error('board shape is wrong');
    Logger.log('SELFTEST PASSED people=' + b.people.join(',') + ' shared=' + b.shared.join(',') +
      ' countdowns=' + JSON.stringify(b.countdowns) + ' dinner=' + b.dinner + ' note=' + b.note +
      ' weather=' + JSON.stringify(b.weather));
  } finally {
    try { Tasks.Tasks.remove(t.list, t.id); } catch (err) { } // already gone when the delete step passed
  }
}

// Shows every task list with how many tasks it holds (open / all, including completed and hidden).
function listReport() {
  (Tasks.Tasklists.list({ maxResults: 50 }).items || []).forEach(function (l) {
    var open = (Tasks.Tasks.list(l.id, { maxResults: 100, showCompleted: false }).items || []).length;
    var all = (Tasks.Tasks.list(l.id, { maxResults: 100, showCompleted: true, showHidden: true }).items || []).length;
    Logger.log('LIST ' + l.title + ' | open=' + open + ' all=' + all + ' | updated=' + l.updated + ' | id=' + l.id);
  });
}
